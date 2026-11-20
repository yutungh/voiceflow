package com.voiceflowkeyboard.ime;

/**
 * Where the voice-style strip should scroll after it is drawn.
 *
 * <p>It used to centre the selected style every time it redrew, so picking Work slid
 * Friends off-screen and getting back meant a swipe. Now the strip keeps its position
 * and moves only as far as needed to show a selected style that would be hidden.
 */
final class StyleStripScroll {
    private StyleStripScroll() {
    }

    /**
     * @param currentX     the strip's scroll position now
     * @param viewport     the strip's visible width
     * @param buttonLeft   the selected button's left edge in the strip's content
     * @param buttonWidth  the selected button's width
     * @param margin       room to leave beside a button that is scrolled into view
     * @return the scroll position to use; {@code currentX} when the button is already fully visible
     */
    static int revealX(int currentX, int viewport, int buttonLeft, int buttonWidth, int margin) {
        int x = Math.max(0, currentX);
        if (viewport <= 0) {
            return x;
        }
        int buttonRight = buttonLeft + buttonWidth;
        if (buttonLeft >= x && buttonRight <= x + viewport) {
            return x;
        }
        if (buttonLeft < x) {
            return Math.max(0, buttonLeft - margin);
        }
        return Math.max(0, buttonRight + margin - viewport);
    }
}
