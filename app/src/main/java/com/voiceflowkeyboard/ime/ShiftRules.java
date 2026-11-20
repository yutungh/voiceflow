package com.voiceflowkeyboard.ime;

/**
 * What a tap on Shift does. Pure, so every rule is unit-tested; the service
 * owns the flags and draws the key.
 *
 * <ul>
 *   <li>Two taps within {@link #DOUBLE_TAP_MS} turn caps lock on, whatever the
 *       first tap did: also at the start of a sentence, where the first tap
 *       turns the automatic capital off (0.8.6; before, that case needed three
 *       taps).</li>
 *   <li>One tap while caps lock is on turns everything off.</li>
 *   <li>Otherwise a tap turns an automatic capital off, or toggles a one-letter
 *       shift.</li>
 * </ul>
 */
final class ShiftRules {
    /** Between the two taps' touch-downs; generous, as phone keyboards are. */
    static final long DOUBLE_TAP_MS = 400;

    /** The state after a tap. */
    static final class State {
        final boolean shift;
        final boolean autoShift;
        final boolean capsLock;
        /** When this tap counts as the first of a double tap; 0 when it cannot. */
        final long lastTapAt;

        State(boolean shift, boolean autoShift, boolean capsLock, long lastTapAt) {
            this.shift = shift;
            this.autoShift = autoShift;
            this.capsLock = capsLock;
            this.lastTapAt = lastTapAt;
        }
    }

    private ShiftRules() {
    }

    /**
     * The state after a tap at {@code now} (a monotonic clock), from the current
     * flags and the previous tap's time ({@code lastTapAt}, 0 for none).
     */
    static State tap(boolean shift, boolean autoShift, boolean capsLock, long lastTapAt, long now) {
        if (capsLock) {
            return new State(false, false, false, 0);
        }
        boolean secondTap = lastTapAt > 0 && now >= lastTapAt && now - lastTapAt <= DOUBLE_TAP_MS;
        if (secondTap) {
            return new State(true, false, true, 0);
        }
        if (autoShift && !shift) {
            // The automatic capital goes; this tap may still start a double tap.
            return new State(false, false, false, now);
        }
        return new State(!shift, false, false, now);
    }
}
