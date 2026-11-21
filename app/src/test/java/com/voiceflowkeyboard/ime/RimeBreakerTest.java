package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
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

        volatile boolean diskFails;

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }

        @Override
        public boolean putDurably(String key, String value) {
            if (diskFails) {
                return false;
            }
            values.put(key, value);
            return true;
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
    public void anUnknownExitDuringStartUpCountsAsACrash() {
        breaker.markStarting(T0);
        breaker.reviewPreviousRun(RimeBreaker.PreviousExit.UNKNOWN, T0 + HOUR);
        java.util.List<String> failures = breaker.recentFailures(T0 + HOUR);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).endsWith(":crash"));
    }

    @Test
    public void unknownExitsWhileLiveTripOnlyWhenTheyComeFast() {
        // Old Android keeps no exit reasons: a few deaths while Rime was live
        // (the system reclaiming memory, most likely) do not turn it off...
        for (int i = 0; i < RimeBreaker.UNKNOWN_LIMIT - 1; i++) {
            breaker.markActive(T0 + i * 1000);
            assertFalse(breaker.reviewPreviousRun(RimeBreaker.PreviousExit.UNKNOWN, T0 + i * 1000 + 500));
        }
        assertFalse(breaker.tripped());
        // ...but one more within the hour is a crash loop.
        breaker.markActive(T0 + 10_000);
        assertTrue(breaker.reviewPreviousRun(RimeBreaker.PreviousExit.UNKNOWN, T0 + 10_500));
        assertEquals(RimeBreaker.KIND_UNKNOWN, breaker.trippedReason());
    }

    @Test
    public void unknownExitsSpreadOverTheDayDoNotTrip() {
        for (int i = 0; i < RimeBreaker.UNKNOWN_LIMIT + 2; i++) {
            long at = T0 + i * 2 * HOUR;
            breaker.markActive(at);
            assertFalse(breaker.reviewPreviousRun(RimeBreaker.PreviousExit.UNKNOWN, at + 1000));
        }
        assertFalse(breaker.tripped());
    }

    @Test
    public void aMarkerThatCannotBeWrittenIsReported() {
        store.diskFails = true;
        assertFalse(breaker.markStarting(T0));
        assertFalse(breaker.markActive(T0));
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
