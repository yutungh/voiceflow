package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Shift's taps (0.8.6): a double tap locks caps from any state, one tap releases it. */
public class ShiftRulesTest {
    private static ShiftRules.State tap(ShiftRules.State s, long now) {
        return ShiftRules.tap(s.shift, s.autoShift, s.capsLock, s.lastTapAt, now);
    }

    private static final ShiftRules.State OFF = new ShiftRules.State(false, false, false, 0);
    private static final ShiftRules.State AUTO = new ShiftRules.State(false, true, false, 0);

    @Test public void oneTapIsOneLetterShift() {
        ShiftRules.State s = tap(OFF, 1000);
        assertTrue(s.shift);
        assertFalse(s.capsLock);
        assertEquals(1000, s.lastTapAt);
    }

    @Test public void twoQuickTapsLockCaps() {
        ShiftRules.State s = tap(tap(OFF, 1000), 1250);
        assertTrue(s.capsLock);
        assertTrue(s.shift);
        assertEquals("a third tap cannot count as a second", 0, s.lastTapAt);
    }

    @Test public void atTheStartOfASentenceTwoQuickTapsStillLockCaps() {
        // Before 0.8.6 the first tap cleared the automatic capital and forgot it was a tap.
        ShiftRules.State first = tap(AUTO, 1000);
        assertFalse("the first tap turns the automatic capital off", first.autoShift || first.shift);
        ShiftRules.State second = tap(first, 1300);
        assertTrue(second.capsLock);
    }

    @Test public void slowTapsNeverLock() {
        ShiftRules.State s = tap(tap(OFF, 1000), 1000 + ShiftRules.DOUBLE_TAP_MS + 1);
        assertFalse(s.capsLock);
        assertFalse("the second slow tap turns shift off", s.shift);
    }

    @Test public void exactlyTheWindowStillCounts() {
        assertTrue(tap(tap(OFF, 1000), 1000 + ShiftRules.DOUBLE_TAP_MS).capsLock);
    }

    @Test public void oneTapReleasesCapsLock() {
        ShiftRules.State locked = tap(tap(OFF, 1000), 1100);
        ShiftRules.State released = tap(locked, 1150);
        assertFalse(released.capsLock);
        assertFalse(released.shift);
        assertFalse(released.autoShift);
        // And a quick tap after the release is a plain shift, not caps lock again.
        ShiftRules.State next = tap(released, 1200);
        assertTrue(next.shift);
        assertFalse(next.capsLock);
    }

    @Test public void aClockThatWentBackwardsIsNotADoubleTap() {
        assertFalse(tap(tap(OFF, 5000), 4000).capsLock);
    }
}
