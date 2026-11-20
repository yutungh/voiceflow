package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StyleStripScrollTest {
    private static final int VIEWPORT = 400;
    private static final int MARGIN = 8;

    @Test
    public void pickingAVisibleStyleDoesNotMoveTheStrip() {
        // Friends at 0-90, Work at 94-184: both on screen; picking Work keeps Friends in view.
        assertEquals(0, StyleStripScroll.revealX(0, VIEWPORT, 94, 90, MARGIN));
    }

    @Test
    public void aRedrawKeepsTheUsersScrollPosition() {
        assertEquals(120, StyleStripScroll.revealX(120, VIEWPORT, 300, 90, MARGIN));
    }

    @Test
    public void aStyleOffTheRightEdgeIsRevealedJustEnough() {
        // Button 600-690 with the strip at 0: scroll so its right edge plus margin fits.
        assertEquals(298, StyleStripScroll.revealX(0, VIEWPORT, 600, 90, MARGIN));
    }

    @Test
    public void aStyleOffTheLeftEdgeIsRevealedJustEnough() {
        assertEquals(42, StyleStripScroll.revealX(300, VIEWPORT, 50, 90, MARGIN));
        assertEquals(0, StyleStripScroll.revealX(300, VIEWPORT, 4, 90, MARGIN));
    }

    @Test
    public void anUnmeasuredStripStaysPut() {
        assertEquals(0, StyleStripScroll.revealX(-5, 0, 600, 90, MARGIN));
    }
}
