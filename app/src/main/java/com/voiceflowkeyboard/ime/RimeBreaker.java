package com.voiceflowkeyboard.ime;

import java.util.ArrayList;
import java.util.List;

/**
 * The crash-loop breaker for the Rime engine (0.9.0).
 *
 * <p>Rime runs in the keyboard's own process, so a native crash takes the
 * keyboard down with it. The first one cannot be prevented; this stops the
 * repeats. Each failure is recorded with its time: a native crash in an
 * earlier run (found through the exit reasons Android keeps, see
 * {@link RimeEngine}), a hang the watchdog caught, or Rime failing to start.
 * When {@link #LIMIT} of them fall within {@link #WINDOW_MS}, the breaker trips:
 * Rime is not loaded again, Classic serves, and the status file says why,
 * until she chooses Rime again in Settings ({@link #reset}).
 *
 * <p>Two markers tell a crash from an ordinary exit. {@code active} is written
 * once when Rime starts and cleared when it stops cleanly, never per
 * keystroke; {@code starting} brackets loading the library and native
 * start-up, the riskiest calls. Both are written durably before the native
 * code they guard runs; if that write fails, Rime is not started. Those disk
 * writes happen outside the lock the UI thread takes.
 *
 * <p>Android before 11 keeps no exit reasons, so a death while Rime was live
 * cannot be told from the system reclaiming memory. Those count as
 * {@link #KIND_UNKNOWN} and trip the breaker only when {@link #UNKNOWN_LIMIT}
 * of them come within {@link #UNKNOWN_WINDOW_MS}, the shape of a crash loop.
 *
 * <p>Pure Java over a small key-value {@link Store} (SharedPreferences on the
 * phone), so it is unit tested directly.
 */
final class RimeBreaker {

    /** Failures within the window that turn Rime off. */
    static final int LIMIT = 3;
    static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    /** Unexplained deaths (Android 8-10) that turn Rime off, and their window. */
    static final int UNKNOWN_LIMIT = 5;
    static final long UNKNOWN_WINDOW_MS = 60L * 60 * 1000;

    static final String KIND_CRASH = "crash";
    static final String KIND_HANG = "hang";
    static final String KIND_START = "start";
    static final String KIND_UNKNOWN = "unknown-exit";

    interface Store {
        /** The stored value, or "" when there is none. */
        String get(String key);

        /** Stores a value; may finish in the background ("" removes it). */
        void put(String key, String value);

        /** Stores a value on disk before returning; false when that failed. */
        boolean putDurably(String key, String value);
    }

    static final String KEY_FAILURES = "rime_failures";
    static final String KEY_TRIPPED = "rime_tripped";
    static final String KEY_ACTIVE = "rime_active_since";
    static final String KEY_STARTING = "rime_starting_since";

    private final Store store;

    RimeBreaker(Store store) {
        this.store = store;
    }

    /** True when Rime must not be loaded. */
    synchronized boolean tripped() {
        return !store.get(KEY_TRIPPED).isEmpty();
    }

    /** Why it tripped ("hang", "crash", "start", "unknown-exit"), or "". */
    synchronized String trippedReason() {
        String value = store.get(KEY_TRIPPED);
        int colon = value.indexOf(':');
        return colon < 0 ? "" : value.substring(colon + 1);
    }

    /** When it tripped, or 0. */
    synchronized long trippedAt() {
        String value = store.get(KEY_TRIPPED);
        int colon = value.indexOf(':');
        return colon < 0 ? 0 : parse(value.substring(0, colon));
    }

    /** Failures still inside the window, oldest first, as "time:kind". */
    synchronized List<String> recentFailures(long now) {
        List<String> kept = new ArrayList<>();
        for (String item : store.get(KEY_FAILURES).split(",")) {
            int colon = item.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            long at = parse(item.substring(0, colon));
            if (at > 0 && now - at < WINDOW_MS && at <= now) {
                kept.add(item);
            }
        }
        return kept;
    }

    /**
     * Records one failure. Returns true when this one tripped the breaker.
     * Written in the background: a hang is recorded on the UI thread, which
     * must not wait for the disk.
     */
    synchronized boolean recordFailure(String kind, long now) {
        List<String> kept = recentFailures(now);
        kept.add(now + ":" + kind);
        // Keep the list short, dropping the oldest unexplained exit first so a run of them never pushes out a real failure.
        while (kept.size() > (LIMIT + UNKNOWN_LIMIT) * 2) {
            int drop = 0;
            for (int i = 0; i < kept.size(); i++) {
                if (kept.get(i).endsWith(":" + KIND_UNKNOWN)) {
                    drop = i;
                    break;
                }
            }
            kept.remove(drop);
        }
        store.put(KEY_FAILURES, String.join(",", kept));
        if (tripped()) {
            return false;
        }
        int known = 0;
        int unknown = 0;
        for (String item : kept) {
            boolean isUnknown = item.endsWith(":" + KIND_UNKNOWN);
            if (!isUnknown) {
                known++;
            } else if (now - parse(item.substring(0, item.indexOf(':'))) < UNKNOWN_WINDOW_MS) {
                unknown++;
            }
        }
        if (known >= LIMIT || unknown >= UNKNOWN_LIMIT) {
            store.put(KEY_TRIPPED, now + ":" + kind);
            return true;
        }
        return false;
    }

    /** Loading and starting Rime begins. False when the marker could not be written: do not start then. */
    boolean markStarting(long now) {
        return store.putDurably(KEY_STARTING, String.valueOf(now));
    }

    /** Native start-up returned; Rime is now live in this process. False when the marker could not be written. */
    boolean markActive(long now) {
        boolean ok = store.putDurably(KEY_ACTIVE, String.valueOf(now));
        return store.putDurably(KEY_STARTING, "") && ok;
    }

    /** Rime was stopped cleanly, or never started. */
    void markInactive() {
        store.putDurably(KEY_STARTING, "");
        store.putDurably(KEY_ACTIVE, "");
    }

    /** How the previous process ended, as far as Android can say. */
    enum PreviousExit {
        /** No record, or nothing Rime could have caused (killed for memory, updated, ...). */
        ORDINARY,
        /** A native crash in the previous process. */
        NATIVE_CRASH,
        /** Android keeps no exit reasons before API 30: nothing is known. */
        UNKNOWN
    }

    /** When the markers say Rime was live (or starting) in an earlier run, or 0. */
    synchronized long liveSince() {
        long starting = parse(store.get(KEY_STARTING));
        long active = parse(store.get(KEY_ACTIVE));
        return starting > 0 ? starting : active;
    }

    /**
     * Called once per process before Rime is loaded. If the markers say Rime
     * was live when the previous process died, the exit decides whether that
     * counts: a native crash always does, and so does any death during
     * loading or start-up, since nothing else runs then; an unexplained death
     * while Rime was merely live (old Android) counts as {@link #KIND_UNKNOWN}.
     * Returns true when this tripped the breaker.
     */
    boolean reviewPreviousRun(PreviousExit exit, long now) {
        boolean starting = parse(store.get(KEY_STARTING)) > 0;
        boolean active = parse(store.get(KEY_ACTIVE)) > 0;
        store.putDurably(KEY_STARTING, "");
        store.putDurably(KEY_ACTIVE, "");
        if (!starting && !active) {
            return false;
        }
        if (exit == PreviousExit.NATIVE_CRASH || (starting && exit == PreviousExit.UNKNOWN)) {
            return recordFailure(KIND_CRASH, now);
        }
        if (exit == PreviousExit.UNKNOWN) {
            return recordFailure(KIND_UNKNOWN, now);
        }
        return false;
    }

    /** She chose Rime again: forget the failures and close the breaker. */
    synchronized void reset() {
        store.put(KEY_FAILURES, "");
        store.put(KEY_TRIPPED, "");
    }

    private static long parse(String value) {
        try {
            return value.isEmpty() ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
