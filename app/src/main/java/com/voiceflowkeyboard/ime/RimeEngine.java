package com.voiceflowkeyboard.ime;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Rime engine as the keyboard sees it (0.9.0): one librime instance in
 * this process, driven from one worker thread, with Classic always ready to
 * take over.
 *
 * <ul>
 *   <li><b>Start-up runs off the UI thread.</b> {@link #start} copies the data on
 *       first use and starts librime on the worker; until {@link #isReady},
 *       callers use Classic.</li>
 *   <li><b>Every native call runs on the worker.</b> A keystroke's query waits
 *       at most {@link #QUERY_TIMEOUT_MS}; past that the watchdog counts a hang,
 *       the keystroke is answered by Classic, and no new query is sent until
 *       the stuck one returns.</li>
 *   <li><b>Failures trip the breaker</b> ({@link RimeBreaker}): crashes found in
 *       Android's exit records, hangs, failed start-ups. Once tripped, Rime is
 *       not loaded again until she chooses it in Settings.</li>
 *   <li><b>Nothing typed is lost:</b> a query that fails returns null and the
 *       caller asks Classic for the same buffer; the buffer itself lives in
 *       {@link PinyinSession}, never here.</li>
 * </ul>
 *
 * <p>Platform details (the native library, the data copy, exit records, the
 * clock and the UI-thread handler) come in through small interfaces, so the
 * logic is unit tested on the JVM with a fake backend.
 */
final class RimeEngine {

    /** The native calls ({@link RimeNative} on the phone). */
    interface Backend {
        String start(String shared, String prebuilt, String user);

        void stop();

        String[] query(int mode, String input, int limit);

        boolean learn(int mode, String input, String text);

        String version();
    }

    /** What a start needs before librime is called. */
    interface Platform {
        /** Loads the native library; false when it is missing or refuses to load. */
        boolean loadLibrary();

        /** Copies (once) and checks the data; returns the folders, or null with {@link #installError}. */
        Dirs installData();

        String installError();

        /** How the previous process ended ({@link RimeBreaker#reviewPreviousRun}). */
        RimeBreaker.PreviousExit previousExit(long since);

        long now();

        /** Runs {@code task} on the UI thread after {@code delayMs}. */
        void postDelayed(Runnable task, long delayMs);

        /** Records the last start's outcome for the status file. */
        void recordStart(String outcome);
    }

    static final class Dirs {
        final File shared;
        final File prebuilt;
        final File user;
        final String dataVersion;

        Dirs(File shared, File prebuilt, File user, String dataVersion) {
            this.shared = shared;
            this.prebuilt = prebuilt;
            this.user = user;
            this.dataVersion = dataVersion;
        }
    }

    enum State {
        /** Not started (or stopped). */
        OFF,
        /** Starting on the worker; Classic serves. */
        STARTING,
        READY,
        /** Could not start in this process (library, data, start-up error); Classic serves. */
        FAILED,
        /** The breaker is tripped; Rime is not loaded at all. */
        DISABLED
    }

    interface Listener {
        /** Called on the UI thread when {@link #state} changes. */
        void onRimeStateChanged(State state);
    }

    static final int MODE_T9 = 0;
    static final int MODE_QWERTY = 1;

    /** A keystroke waits this long for Rime before Classic answers it. */
    static final long QUERY_TIMEOUT_MS = 250;
    /** Start-up (data copy included) longer than this counts as a hang. */
    static final long START_TIMEOUT_MS = 60_000;
    /** Native errors (not crashes) after which Rime stops being asked in this process. */
    static final int ERROR_LIMIT = 10;

    private final Backend backend;
    private final Platform platform;
    private final RimeBreaker breaker;
    private final ExecutorService worker;

    private volatile State state = State.OFF;
    private volatile String detail = "";
    private volatile Dirs dirs;
    private volatile int startGeneration;
    private Listener listener;
    /** The last query sent to the worker; UI thread only. */
    private Future<String[]> pending;
    /** Whether librime is started; worker thread only. */
    private boolean nativeRunning;
    private int errors;
    private int hangs;

    RimeEngine(Backend backend, Platform platform, RimeBreaker breaker, ExecutorService worker) {
        this.backend = backend;
        this.platform = platform;
        this.breaker = breaker;
        this.worker = worker;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    State state() {
        return state;
    }

    /** A short code for the status file and Settings: why it is not ready, or the data version. */
    String detail() {
        return detail;
    }

    boolean isReady() {
        return state == State.READY;
    }

    int hangs() {
        return hangs;
    }

    RimeBreaker breaker() {
        return breaker;
    }

    /**
     * Starts Rime unless it is running, starting, or turned off by the
     * breaker. Safe to call on every switch into Chinese.
     */
    void start() {
        if (state == State.READY || state == State.STARTING || state == State.FAILED) {
            return;
        }
        if (breaker.tripped()) {
            setState(State.DISABLED, "breaker-" + breaker.trippedReason());
            return;
        }
        final int generation = ++startGeneration;
        setState(State.STARTING, "");
        try {
            worker.execute(() -> runStart(generation));
        } catch (RejectedExecutionException shutDown) {
            setState(State.FAILED, "shut-down");
            return;
        }
        platform.postDelayed(() -> {
            if (state == State.STARTING && startGeneration == generation) {
                failure(RimeBreaker.KIND_HANG);
                setState(State.FAILED, "start-timeout");
            }
        }, START_TIMEOUT_MS);
    }

    /** Worker thread. */
    private void runStart(int generation) {
        long now = platform.now();
        // The previous process: did it die with Rime live?
        long since = breaker.liveSince();
        if (since > 0 && breaker.reviewPreviousRun(platform.previousExit(since), now)) {
            post(() -> setState(State.DISABLED, "breaker-" + breaker.trippedReason()));
            return;
        }
        if (breaker.tripped()) {
            post(() -> setState(State.DISABLED, "breaker-" + breaker.trippedReason()));
            return;
        }
        if (!platform.loadLibrary()) {
            platform.recordStart("no-library");
            post(() -> setState(State.FAILED, "no-library"));
            return;
        }
        Dirs installed = platform.installData();
        if (installed == null) {
            String why = "data-" + platform.installError();
            platform.recordStart(why);
            post(() -> setState(State.FAILED, why));
            return;
        }
        String error = startNative(installed);
        if (!error.isEmpty()) {
            breaker.recordFailure(RimeBreaker.KIND_START, platform.now());
            platform.recordStart("error-" + error);
            post(() -> setState(State.FAILED, "start-" + error));
            return;
        }
        dirs = installed;
        long took = platform.now() - now;
        platform.recordStart("ok " + took + "ms " + installed.dataVersion);
        post(() -> {
            if (startGeneration == generation && state == State.STARTING) {
                setState(State.READY, installed.dataVersion);
            }
        });
    }

    /** Worker thread: native start plus a warm-up, bracketed by the breaker's markers. */
    private String startNative(Dirs installed) {
        installed.user.mkdirs();
        breaker.markStarting(platform.now());
        String error;
        try {
            error = backend.start(installed.shared.getPath(), installed.prebuilt.getPath(), installed.user.getPath());
        } catch (RuntimeException | LinkageError e) {
            error = "exception";
        }
        if (error == null) {
            error = "null";
        }
        if (!error.isEmpty()) {
            breaker.markInactive();
            return error;
        }
        nativeRunning = true;
        breaker.markActive(platform.now());
        // Pages the dictionary in, so her first keystroke does not wait for it.
        try {
            backend.query(MODE_T9, "64426", 30);
            backend.query(MODE_QWERTY, "nihao", 30);
        } catch (RuntimeException | LinkageError ignored) {
            // A failing query shows up again on her first keystroke and is counted there.
        }
        return "";
    }

    /** Stops Rime cleanly (the keyboard is going away). */
    void stop() {
        if (state != State.READY && state != State.STARTING) {
            return;
        }
        startGeneration++;
        setState(State.OFF, "");
        try {
            worker.execute(() -> {
                if (nativeRunning) {
                    stopNative();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Already shut down.
        }
    }

    /**
     * Candidates from Rime for {@code input}, or null when Rime cannot answer
     * right now (not ready, busy with a stuck call, an error, or the watchdog's
     * timeout), in which case the caller asks Classic. UI thread.
     */
    List<PinyinCandidate> query(boolean keypad, String input, int limit) {
        if (state != State.READY || input == null || input.isEmpty()) {
            return null;
        }
        if (pending != null && !pending.isDone()) {
            return null;
        }
        final int mode = keypad ? MODE_T9 : MODE_QWERTY;
        Future<String[]> future;
        try {
            future = worker.submit(() -> backend.query(mode, input, limit));
        } catch (RejectedExecutionException shutDown) {
            return null;
        }
        pending = future;
        String[] flat;
        try {
            flat = future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            hangs++;
            failure(RimeBreaker.KIND_HANG);
            return null;
        } catch (ExecutionException error) {
            countError();
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (flat == null) {
            countError();
            return null;
        }
        errors = 0;
        return parse(flat, input.length());
    }

    /** Native candidates (text, consumed, spelling, flags per candidate) as the keyboard's. */
    static List<PinyinCandidate> parse(String[] flat, int inputLength) {
        if (flat.length < 4) {
            return Collections.emptyList();
        }
        List<PinyinCandidate> out = new ArrayList<>(flat.length / 4);
        for (int i = 0; i + 3 < flat.length; i += 4) {
            String text = flat[i];
            int consumed;
            try {
                consumed = Integer.parseInt(flat[i + 1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (text == null || text.isEmpty() || consumed <= 0) {
                continue;
            }
            consumed = Math.min(consumed, inputLength);
            boolean completion = "c".equals(flat[i + 3]);
            out.add(new PinyinCandidate(text, consumed, completion, -out.size(), flat[i + 2]));
        }
        return out;
    }

    /**
     * Teaches Rime that {@code text} was chosen for {@code input} (the part of
     * the buffer it read). Fire and forget, on the worker.
     */
    void learn(boolean keypad, String input, String text) {
        if (state != State.READY || input == null || input.isEmpty() || text == null || text.isEmpty()) {
            return;
        }
        final int mode = keypad ? MODE_T9 : MODE_QWERTY;
        try {
            worker.execute(() -> {
                try {
                    backend.learn(mode, input, text);
                } catch (RuntimeException | LinkageError ignored) {
                    // Learning is best effort; the choice itself is already committed.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    /**
     * "Forget my typing": stops Rime, removes its user dictionary and starts it
     * again. Runs on the worker; {@code done} gets true on success, on the UI thread.
     */
    void forget(File userDir, java.util.function.Consumer<Boolean> done) {
        // Queued behind any start in progress, so the user dictionary is never
        // removed while librime has it open.
        if (state == State.READY) {
            setState(State.STARTING, "forgetting");
        }
        final int generation = ++startGeneration;
        try {
            worker.execute(() -> {
                Dirs current = dirs;
                boolean wasRunning = nativeRunning;
                if (wasRunning) {
                    stopNative();
                }
                boolean cleared = clearUserData(current != null ? current.user : userDir);
                String error = wasRunning && current != null ? startNative(current) : "";
                post(() -> {
                    if (startGeneration == generation && state == State.STARTING) {
                        if (wasRunning && error.isEmpty()) {
                            setState(State.READY, current.dataVersion);
                        } else if (wasRunning) {
                            setState(State.FAILED, "start-" + error);
                        } else {
                            // Nothing was running: the next switch into Chinese starts it.
                            setState(State.OFF, "");
                        }
                    }
                    done.accept(cleared && error.isEmpty());
                });
            });
        } catch (RejectedExecutionException shutDown) {
            done.accept(false);
        }
    }

    /** Worker thread. */
    private void stopNative() {
        try {
            backend.stop();
        } catch (RuntimeException | LinkageError ignored) {
            // Stopping is best effort.
        }
        nativeRunning = false;
        breaker.markInactive();
    }

    /** Removes everything Rime learned (its user dictionary and snapshots). */
    static boolean clearUserData(File userDir) {
        if (userDir == null || !userDir.exists()) {
            return true;
        }
        File[] children = userDir.listFiles();
        boolean ok = true;
        if (children != null) {
            for (File child : children) {
                ok &= removeTree(child);
            }
        }
        return ok;
    }

    private static boolean removeTree(File file) {
        File[] children = file.isDirectory() ? file.listFiles() : null;
        boolean ok = true;
        if (children != null) {
            for (File child : children) {
                ok &= removeTree(child);
            }
        }
        return file.delete() && ok;
    }

    private void countError() {
        errors++;
        if (errors >= ERROR_LIMIT && state == State.READY) {
            setState(State.FAILED, "errors");
        }
    }

    /** A hang or failed start: counted by the breaker; tripping turns Rime off now. */
    private void failure(String kind) {
        if (breaker.recordFailure(kind, platform.now())) {
            setState(State.DISABLED, "breaker-" + kind);
        }
    }

    /** She chose Rime again in Settings: close the breaker and allow a fresh start. */
    void reenable() {
        breaker.reset();
        if (state == State.DISABLED || state == State.FAILED) {
            setState(State.OFF, "");
        }
    }

    private void post(Runnable task) {
        platform.postDelayed(task, 0);
    }

    private void setState(State next, String why) {
        state = next;
        detail = why;
        if (listener != null) {
            listener.onRimeStateChanged(next);
        }
    }
}
