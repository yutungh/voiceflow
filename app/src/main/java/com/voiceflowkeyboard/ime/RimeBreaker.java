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
 * keystroke; {@code starting} brackets native start-up, the riskiest call.
 *
 * <p>Pure Java over a small key-value {@link Store} (SharedPreferences on the
 * phone), so it is unit tested directly.
 */
final class RimeBreaker {

    /** Failures within the window that turn Rime off. */
    static final int LIMIT = 3;
    static final long WINDOW_MS = 24L * 60 * 60 * 1000;

    static final String KIND_CRASH = "crash";
    static final String KIND_HANG = "hang";
    static final String KIND_START = "start";

    interface Store {
        /** The stored value, or "" when there is none. */
        String get(String key);

        /** Stores a value durably before returning ("" removes it). */
        void put(String key, String value);
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

    /** Why it tripped ("hang", "crash", "start"), or "". */
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
     */
    synchronized boolean recordFailure(String kind, long now) {
        List<String> kept = recentFailures(now);
        kept.add(now + ":" + kind);
        while (kept.size() > LIMIT * 2) {
            kept.remove(0);
        }
        store.put(KEY_FAILURES, String.join(",", kept));
        if (!tripped() && kept.size() >= LIMIT) {
            store.put(KEY_TRIPPED, now + ":" + kind);
            return true;
        }
        return false;
    }

    /** Rime's native start-up begins (written before the call). */
    synchronized void markStarting(long now) {
        store.put(KEY_STARTING, String.valueOf(now));
    }

    /** Native start-up returned; Rime is now live in this process. */
    synchronized void markActive(long now) {
        store.put(KEY_STARTING, "");
        store.put(KEY_ACTIVE, String.valueOf(now));
    }

    /** Rime was stopped cleanly. */
    synchronized void markInactive() {
        store.put(KEY_STARTING, "");
        store.put(KEY_ACTIVE, "");
    }

    /** How the previous process ended, as far as Android can say. */
    enum PreviousExit {
        /** No record, or nothing Rime could have caused (killed for memory, updated, ...). */
        ORDINARY,
        /** A native crash that names libvfrime, or one whose details are not available. */
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
     * counts: a native crash always does; a death during start-up counts even
     * when the reason is unknown (old Android), since nothing else runs then.
     * Returns true when this tripped the breaker.
     */
    synchronized boolean reviewPreviousRun(PreviousExit exit, long now) {
        boolean starting = parse(store.get(KEY_STARTING)) > 0;
        boolean active = parse(store.get(KEY_ACTIVE)) > 0;
        store.put(KEY_STARTING, "");
        store.put(KEY_ACTIVE, "");
        if (!starting && !active) {
            return false;
        }
        if (exit == PreviousExit.NATIVE_CRASH || (starting && exit == PreviousExit.UNKNOWN)) {
            return recordFailure(KIND_CRASH, now);
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
