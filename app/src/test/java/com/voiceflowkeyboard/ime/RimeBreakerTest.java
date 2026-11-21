package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** The Rime crash-loop breaker (0.9.0). */
public class RimeBreakerTest {

    static final class MemoryStore implements RimeBreaker.Store {
        final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key) {
            String value = values.get(key);
            return value == null ? "" : value;
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }
    }

    private static final long T0 = 1_800_000_000_000L;
    private static final long HOUR = 60L * 60 * 1000;

    private MemoryStore store;
    private RimeBreaker breaker;

    @Before
    public void setUp() {
        store = new MemoryStore();
        breaker = new RimeBreaker(store);
    }

    @Test
    public void tripsOnTheThirdFailureWithinADay() {
        assertFalse(breaker.recordFailure(RimeBreaker.KIND_HANG, T0));
        assertFalse(breaker.recordFailure(RimeBreaker.KIND_CRASH, T0 + HOUR));
        assertFalse(breaker.tripped());
        assertTrue(breaker.recordFailure(RimeBreaker.KIND_HANG, T0 + 2 * HOUR));
        assertTrue(breaker.tripped());
        assertEquals("hang", breaker.trippedReason());
        assertEquals(T0 + 2 * HOUR, breaker.trippedAt());
        // Survives a new process: a fresh breaker over the same store.
        assertTrue(new RimeBreaker(store).tripped());
    }

    @Test
    public void failuresOlderThanTheWindowDoNotCount() {
        breaker.recordFailure(RimeBreaker.KIND_HANG, T0);
        breaker.recordFailure(RimeBreaker.KIND_HANG, T0 + HOUR);
        assertFalse(breaker.recordFailure(RimeBreaker.KIND_HANG, T0 + 25 * HOUR));
        assertFalse(breaker.tripped());
        assertEquals(1, breaker.recentFailures(T0 + 25 * HOUR).size());
    }

    @Test
    public void choosingRimeAgainResetsIt() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(RimeBreaker.KIND_START, T0 + i);
        }
        assertTrue(breaker.tripped());
        breaker.reset();
        assertFalse(breaker.tripped());
        assertEquals(0, breaker.recentFailures(T0 + 10).size());
    }

    @Test
    public void aNativeCrashWhileRimeWasLiveCounts() {
        breaker.markStarting(T0);
        breaker.markActive(T0 + 100);
        assertEquals(T0 + 100, breaker.liveSince());
        assertFalse(breaker.reviewPreviousRun(RimeBreaker.PreviousExit.NATIVE_CRASH, T0 + HOUR));
        assertEquals(1, breaker.recentFailures(T0 + HOUR).size());
        // The markers are consumed: the same death is never counted twice.
        assertEquals(0, breaker.liveSince());
        assertFalse(breaker.reviewPreviousRun(RimeBreaker.PreviousExit.NATIVE_CRASH, T0 + HOUR));
        assertEquals(1, breaker.recentFailures(T0 + HOUR).size());
    }

    @Test
    public void anOrdinaryExitDoesNotCount() {
        breaker.markActive(T0);
        breaker.reviewPreviousRun(RimeBreaker.PreviousExit.ORDINARY, T0 + HOUR);
        assertEquals(0, breaker.recentFailures(T0 + HOUR).size());
    }

    @Test
    public void anUnknownExitCountsOnlyDuringStartUp() {
        // Old Android keeps no exit reasons: a death while Rime was merely live is not blamed on it...
        breaker.markActive(T0);
        breaker.reviewPreviousRun(RimeBreaker.PreviousExit.UNKNOWN, T0 + HOUR);
        assertEquals(0, breaker.recentFailures(T0 + HOUR).size());
        // ...but one in the middle of native start-up is.
        breaker.markStarting(T0 + 2 * HOUR);
        breaker.reviewPreviousRun(RimeBreaker.PreviousExit.UNKNOWN, T0 + 3 * HOUR);
        assertEquals(1, breaker.recentFailures(T0 + 3 * HOUR).size());
    }

    @Test
    public void aCleanStopClearsTheMarkers() {
        breaker.markStarting(T0);
        breaker.markActive(T0 + 1);
        breaker.markInactive();
        assertEquals(0, breaker.liveSince());
        assertFalse(breaker.reviewPreviousRun(RimeBreaker.PreviousExit.NATIVE_CRASH, T0 + HOUR));
        assertEquals(0, breaker.recentFailures(T0 + HOUR).size());
    }

    @Test
    public void corruptValuesAreIgnored() {
        store.put(RimeBreaker.KEY_FAILURES, "garbage,12:x,:,abc:def");
        store.put(RimeBreaker.KEY_ACTIVE, "not a number");
        assertEquals(0, breaker.recentFailures(T0).size());
        assertEquals(0, breaker.liveSince());
        assertFalse(breaker.tripped());
    }
}
