package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Rime device gates (0.9.0), run over adb on a real phone by
 * scripts/rime-android/device-gates.ps1, never by her. Each run does one
 * check and writes its result to logcat as one line, {@code VFGATE <json>},
 * then {@code VFGATE-DONE}:
 *
 * <ul>
 *   <li>{@code cold}: from this (fresh) process to Rime's first candidates;</li>
 *   <li>{@code replay}: the replay fixture (assets/rime/replay.tsv, recorded on
 *       the PC by the same core) gives the same candidates here;</li>
 *   <li>{@code latency}: per-key time through the keyboard's own
 *       {@link PinyinSession}, Rime behind it, over the fixture's phrases;</li>
 *   <li>{@code memory}: the process's memory with Rime and Classic loaded;</li>
 *   <li>{@code soak}: random typing, pins, deletes and partial commits for a
 *       while, checking that no typed text is ever lost or changed;</li>
 *   <li>{@code pages}: the page size and that libvfrime.so loaded.</li>
 * </ul>
 *
 * <p>Started only by the adb shell: the manifest guards it with DUMP, which no
 * app holds. It reads and writes nothing of hers; the user dictionary is
 * untouched except by the soak, which learns from its random choices only when
 * started with {@code --ez learn true}.
 */
public final class RimeGateActivity extends Activity {

    private static final String TAG = "VFRimeGate";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView log;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        log = new TextView(this);
        log.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        setContentView(scroll);
        long created = SystemClock.elapsedRealtime();
        String action = getIntent().getStringExtra("action");
        int seconds = getIntent().getIntExtra("seconds", 300);
        boolean learn = getIntent().getBooleanExtra("learn", false);
        Thread worker = new Thread(() -> run(action == null ? "" : action, seconds, learn, created), "vf-rime-gate");
        worker.setDaemon(true);
        worker.start();
    }

    private void run(String action, int seconds, boolean learn, long created) {
        JSONObject result;
        try {
            switch (action) {
                case "cold":
                    result = cold(created);
                    break;
                case "replay":
                    result = replay();
                    break;
                case "latency":
                    result = latency();
                    break;
                case "memory":
                    result = memory();
                    break;
                case "soak":
                    result = soak(seconds, learn);
                    break;
                case "pages":
                    result = pages();
                    break;
                default:
                    result = new JSONObject().put("error", "unknown action '" + action + "'");
            }
            result.put("action", action);
        } catch (Throwable t) {
            result = new JSONObject();
            try {
                result.put("action", action).put("error", t.toString()).put("pass", false);
            } catch (org.json.JSONException ignored) {
                // Unreachable with string values.
            }
        }
        emit("VFGATE " + result);
        emit("VFGATE-DONE");
    }

    private void emit(String line) {
        Log.i(TAG, line);
        main.post(() -> log.append(line + "\n\n"));
    }

    // ------------------------------------------------------------------ checks

    /** Process start (and this activity) to Rime's first candidates, data copy included when it happens. */
    private JSONObject cold(long created) throws Exception {
        boolean installed = installedDataPresent();
        RimeEngine rime = RimeAndroid.engine(this);
        long t0 = SystemClock.elapsedRealtime();
        onMain(() -> {
            rime.start();
            return null;
        });
        if (!waitFor(() -> rime.isReady() || rime.state() == RimeEngine.State.FAILED
                || rime.state() == RimeEngine.State.DISABLED, 120_000)) {
            return new JSONObject().put("pass", false).put("error", "not ready in 120 s");
        }
        long ready = SystemClock.elapsedRealtime();
        if (!rime.isReady()) {
            return new JSONObject().put("pass", false).put("state", rime.state().name()).put("detail", rime.detail());
        }
        List<PinyinCandidate> first = onMain(() -> rime.query(true, "64426", 30));
        long answered = SystemClock.elapsedRealtime();
        long processStart = Process.getStartElapsedRealtime();
        long fromStart = answered - t0;
        return new JSONObject()
                .put("dataWasInstalled", installed)
                .put("startToReadyMs", ready - t0)
                .put("firstQueryMs", answered - ready)
                .put("startToFirstCandidatesMs", fromStart)
                .put("processStartToFirstCandidatesMs", answered - processStart)
                .put("activityToFirstCandidatesMs", answered - created)
                .put("top", first == null || first.isEmpty() ? JSONObject.NULL : first.get(0).text)
                .put("gateMs", 500)
                // The gate is for a start with the data already in place; the first start after install copies it.
                .put("pass", installed && first != null && !first.isEmpty() && fromStart < 500);
    }

    /** The replay fixture's rows must come back exactly as the PC recorded them. */
    private JSONObject replay() throws Exception {
        RimeEngine rime = readyRime();
        List<String[]> rows = fixture();
        int differ = 0;
        JSONArray examples = new JSONArray();
        for (String[] row : rows) {
            boolean keypad = row[0].equals("t9");
            String buffer = row[1];
            List<PinyinCandidate> found = onMain(() -> rime.query(keypad, buffer, 30));
            StringBuilder got = new StringBuilder(row[0]).append('\t').append(buffer);
            if (found != null) {
                for (int i = 0; i < Math.min(5, found.size()); i++) {
                    PinyinCandidate c = found.get(i);
                    got.append('\t').append(c.text).append('\t').append(c.consumed).append('\t').append(c.spelling);
                }
            }
            String want = String.join("\t", row);
            if (found == null || !got.toString().equals(want)) {
                differ++;
                if (examples.length() < 5) {
                    examples.put(new JSONObject().put("want", want).put("got", found == null ? "(no answer)" : got.toString()));
                }
            }
        }
        // The fixture is a first-day user's: anything learned on this phone can reorder it,
        // so the script runs this on a fresh install or after "Forget my typing".
        return new JSONObject().put("rows", rows.size()).put("differ", differ).put("examples", examples)
                .put("pass", !rows.isEmpty() && differ == 0);
    }

    /** Per-key time through PinyinSession on the 9-key pad, Rime behind it. */
    private JSONObject latency() throws Exception {
        PinyinEngine engine = readyKeyboardEngine();
        PinyinConverter converter = onMain(() -> engine.composerFor(InputMode.CHINESE_KEYPAD));
        PinyinSession session = new PinyinSession(converter);
        List<Double> times = new ArrayList<>();
        int fromClassic = 0;
        for (String buffer : phraseBuffers()) {
            // Warm-up is the first phrase; every key after it counts.
            for (int i = 0; i < buffer.length(); i++) {
                char key = buffer.charAt(i);
                double ms = onMain(() -> {
                    long start = System.nanoTime();
                    session.append(key);
                    session.candidates();
                    session.spellingOptions();
                    return (System.nanoTime() - start) / 1e6;
                });
                times.add(ms);
                if (converter instanceof RimeConverter && !((RimeConverter) converter).lastFromRime()) {
                    fromClassic++;
                }
            }
            onMain(() -> session.settle(PinyinSession.SettleReason.CANCELLED));
        }
        double[] sorted = new double[times.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = times.get(i);
        }
        Arrays.sort(sorted);
        double p95 = percentile(sorted, 95);
        return new JSONObject()
                .put("keys", sorted.length)
                .put("p50Ms", round(percentile(sorted, 50)))
                .put("p95Ms", round(p95))
                .put("p99Ms", round(percentile(sorted, 99)))
                .put("maxMs", round(sorted.length == 0 ? 0 : sorted[sorted.length - 1]))
                .put("answeredByClassic", fromClassic)
                .put("rimeHangs", RimeAndroid.engine(this).hangs())
                .put("gateP95Ms", 50)
                .put("pass", sorted.length > 0 && p95 < 50 && fromClassic == 0);
    }

    /** Memory with Rime and Classic both loaded (after the other checks, or on its own). */
    private JSONObject memory() throws Exception {
        readyKeyboardEngine();
        Debug.MemoryInfo info = new Debug.MemoryInfo();
        Debug.getMemoryInfo(info);
        JSONObject out = new JSONObject()
                .put("totalPssKb", info.getTotalPss())
                .put("nativePssKb", info.nativePss)
                .put("dalvikPssKb", info.dalvikPss)
                .put("otherPssKb", info.otherPss)
                .put("nativeHeapAllocatedKb", Debug.getNativeHeapAllocatedSize() / 1024)
                .put("javaHeapUsedKb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024);
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String key : new String[]{"VmRSS", "VmHWM", "RssAnon", "RssFile"}) {
                    if (line.startsWith(key + ":")) {
                        out.put(key + "Kb", Long.parseLong(line.replaceAll("[^0-9]", "")));
                    }
                }
            }
        }
        // Measured, not gated: the plan records memory for the Fold beside speech and multitasking.
        return out.put("pass", true);
    }

    /**
     * Random typing on the 9-key pad for {@code seconds}: keys, the 1 key,
     * backspace, pins, partial and whole commits. After every step the buffer
     * must be exactly what was typed minus what was committed; a commit must be
     * exactly the chosen candidate.
     */
    private JSONObject soak(int seconds, boolean learn) throws Exception {
        PinyinEngine engine = readyKeyboardEngine();
        PinyinConverter base = onMain(() -> engine.composerFor(InputMode.CHINESE_KEYPAD));
        PinyinConverter converter = learn ? base : new PinyinConverter() {
            @Override
            public PinyinLookup lookup() {
                return base.lookup();
            }

            @Override
            public List<PinyinCandidate> candidates(String rawInput, int limit) {
                return base.candidates(rawInput, limit);
            }
        };
        PinyinSession session = new PinyinSession(converter);
        Random random = new Random(20261121L);
        long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        int[] counts = new int[8];
        List<String> violations = new ArrayList<>();
        int pid = Process.myPid();
        int steps = 0;
        double worst = 0;
        while (SystemClock.elapsedRealtime() < until && violations.size() < 20) {
            int roll = random.nextInt(100);
            String before = onMain(session::rawText);
            AtomicReference<String> problem = new AtomicReference<>(null);
            long start = System.nanoTime();
            onMain(() -> {
                step(session, roll, random, before, counts, problem);
                return null;
            });
            worst = Math.max(worst, (System.nanoTime() - start) / 1e6);
            if (problem.get() != null) {
                violations.add(problem.get());
            }
            steps++;
        }
        JSONArray found = new JSONArray();
        for (String v : violations) {
            found.put(v);
        }
        return new JSONObject()
                .put("seconds", seconds)
                .put("steps", steps)
                .put("keys", counts[0]).put("separators", counts[1]).put("backspaces", counts[2])
                .put("pins", counts[3]).put("commits", counts[4]).put("settles", counts[5])
                .put("worstStepMs", round(worst))
                .put("rimeHangs", RimeAndroid.engine(this).hangs())
                .put("rimeState", RimeAndroid.engine(this).state().name())
                .put("pid", pid)
                .put("violations", found)
                .put("pass", violations.isEmpty() && RimeAndroid.engine(this).isReady());
    }

    private static void step(PinyinSession session, int roll, Random random, String before,
                             int[] counts, AtomicReference<String> problem) {
        if (roll < 55 || before.isEmpty()) {
            char key = (char) ('2' + random.nextInt(8));
            boolean taken = session.append(key);
            counts[0]++;
            if (taken && !session.rawText().equals(before + key)) {
                problem.set("append " + key + ": " + before + " -> " + session.rawText());
            } else if (!taken && !session.rawText().equals(before)) {
                problem.set("refused append changed the buffer: " + before + " -> " + session.rawText());
            }
        } else if (roll < 60) {
            session.appendSeparator();
            counts[1]++;
            String after = session.rawText();
            if (!after.equals(before) && !after.equals(before + "'")) {
                problem.set("separator: " + before + " -> " + after);
            }
        } else if (roll < 70) {
            session.backspace();
            counts[2]++;
            if (!session.rawText().equals(before.substring(0, before.length() - 1))) {
                problem.set("backspace: " + before + " -> " + session.rawText());
            }
        } else if (roll < 78) {
            List<PinyinKeypadLookup.SpellingOption> options = session.spellingOptions();
            if (!options.isEmpty()) {
                PinyinKeypadLookup.SpellingOption option = options.get(random.nextInt(options.size()));
                boolean pinned = session.pinSpelling(options.indexOf(option));
                counts[3]++;
                String expected = before.substring(0, option.start) + option.replacement + before.substring(option.end);
                if (pinned && !session.rawText().equals(expected)) {
                    problem.set("pin " + option.label + ": " + before + " -> " + session.rawText());
                }
            }
        } else if (roll < 96) {
            List<PinyinCandidate> candidates = session.candidates();
            if (!candidates.isEmpty()) {
                int index = random.nextInt(Math.min(6, candidates.size()));
                PinyinCandidate chosen = candidates.get(index);
                PinyinSession.Settlement settlement = session.select(index);
                counts[4]++;
                String rest = before.substring(Math.min(chosen.consumed, before.length()));
                while (rest.startsWith("'")) {
                    rest = rest.substring(1);
                }
                if (settlement.action != PinyinSession.Action.COMMIT || !chosen.text.equals(settlement.text)) {
                    problem.set("commit of " + chosen.text + " gave " + settlement.action + " " + settlement.text);
                } else if (!session.rawText().equals(rest)) {
                    problem.set("after commit: " + before + " -> " + session.rawText() + " (want " + rest + ")");
                }
            }
        } else {
            List<PinyinCandidate> candidates = session.candidates();
            String top = candidates.isEmpty() ? null : candidates.get(0).text;
            String expected = null;
            if (top != null) {
                String rest = before.substring(Math.min(candidates.get(0).consumed, before.length()));
                while (rest.startsWith("'")) {
                    rest = rest.substring(1);
                }
                // What the best reading does not read is committed as typed after it.
                expected = top + rest;
            }
            boolean raw = session.shouldAcceptRaw();
            PinyinSession.Settlement settlement = session.settle(PinyinSession.SettleReason.ACCEPT_TOP);
            counts[5]++;
            boolean ok = raw || top == null
                    ? settlement.action == PinyinSession.Action.KEEP_RAW && before.equals(settlement.text)
                    : settlement.action == PinyinSession.Action.COMMIT && expected.equals(settlement.text);
            if (!ok || session.isComposing()) {
                problem.set("settle of " + before + " gave " + settlement.action + " " + settlement.text);
            }
        }
    }

    /** Page size, and whether libvfrime.so loaded and from where. */
    private JSONObject pages() throws Exception {
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        boolean loaded = RimeNative.load();
        File lib = new File(getApplicationInfo().nativeLibraryDir, "libvfrime.so");
        JSONObject out = new JSONObject()
                .put("pageSize", pageSize)
                .put("libraryLoaded", loaded)
                .put("loadError", RimeNative.loadError())
                .put("extracted", lib.exists());
        if (loaded) {
            RimeEngine rime = readyRime();
            List<PinyinCandidate> found = onMain(() -> rime.query(true, "64426", 30));
            out.put("top", found == null || found.isEmpty() ? JSONObject.NULL : found.get(0).text);
            return out.put("pass", found != null && !found.isEmpty());
        }
        return out.put("pass", false);
    }

    // ------------------------------------------------------------------ helpers

    private RimeEngine readyRime() throws Exception {
        RimeEngine rime = RimeAndroid.engine(this);
        onMain(() -> {
            rime.start();
            return null;
        });
        if (!waitFor(rime::isReady, 120_000)) {
            throw new IllegalStateException("Rime not ready: " + rime.state() + " " + rime.detail());
        }
        return rime;
    }

    private PinyinEngine keyboardEngine;
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    /** Classic and Rime both ready, as the keyboard has them. */
    private PinyinEngine readyKeyboardEngine() throws Exception {
        RimeEngine rime = readyRime();
        if (keyboardEngine == null) {
            keyboardEngine = onMain(() -> new PinyinEngine(getAssets(), loader, main, () -> { }, rime, () -> true));
        }
        PinyinEngine engine = keyboardEngine;
        onMain(() -> {
            engine.prepare(InputMode.CHINESE_KEYPAD);
            return null;
        });
        if (!waitFor(() -> onMainQuiet(() -> engine.composerFor(InputMode.CHINESE_KEYPAD)) instanceof RimeConverter, 120_000)) {
            throw new IllegalStateException("Classic 9-key index not ready");
        }
        return engine;
    }

    private boolean installedDataPresent() {
        try (InputStream in = getAssets().open(RimeData.MANIFEST)) {
            RimeData.Manifest manifest = RimeData.readManifest(in);
            return new File(new File(RimeAndroid.base(this), "data-" + manifest.version), ".complete").isFile();
        } catch (IOException e) {
            return false;
        }
    }

    private List<String[]> fixture() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open(RimeData.ROOT + "/replay.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    rows.add(line.split("\t", -1));
                }
            }
        }
        return rows;
    }

    /** The fixture's whole 9-key phrases (all digits), typed key by key. */
    private List<String> phraseBuffers() throws IOException {
        List<String> out = new ArrayList<>();
        for (String[] row : fixture()) {
            if (row[0].equals("t9") && row[1].matches("[2-9]{4,}")) {
                out.add(row[1]);
            }
        }
        return out;
    }

    private <T> T onMain(Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.call();
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        main.post(() -> {
            try {
                value.set(task.call());
            } catch (Exception e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("UI thread did not answer in 30 s");
        }
        if (error.get() != null) {
            throw error.get();
        }
        return value.get();
    }

    private <T> T onMainQuiet(Callable<T> task) {
        try {
            return onMain(task);
        } catch (Exception e) {
            return null;
        }
    }

    private interface Condition {
        boolean met();
    }

    private static boolean waitFor(Condition condition, long timeoutMs) throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < until) {
            if (condition.met()) {
                return true;
            }
            Thread.sleep(2);
        }
        return condition.met();
    }

    private static double percentile(double[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.2f", value));
    }
}
