package com.voiceflowkeyboard.ime;

/**
 * Where the keyboard splits and how big its keys are.
 *
 * <p>Pure arithmetic over screen dimensions, deliberately free of Android types
 * so the decisions can be unit tested against real device measurements. The
 * service reads the values out of {@code Configuration} and delegates here.
 */
final class KeyboardGeometry {

    /** Ordinary phone, upright. Also the usual accessibility touch-target size. */
    static final int KEY_HEIGHT_DP = 48;

    /** Ordinary phone, landscape — shorter because the window is. */
    static final int KEY_HEIGHT_LANDSCAPE_DP = 42;

    /**
     * Key height once the keyboard splits, which only happens on a large screen.
     *
     * <p>Until now a large screen got a phone's keyboard: 48dp keys and a 192dp
     * four-row surface on a display 835dp tall. dp is meant to hold physical
     * size constant across devices, so that is defensible in principle, but it
     * leaves an unfolded Fold noticeably smaller than the keyboard beside it —
     * which is the complaint.
     *
     * <p>52dp keeps the split layout slightly larger than the phone layout while
     * bringing it much closer to Samsung's compact five-row split keyboard.
     *
     * <p>Note the visible key face is about 6dp shorter than these numbers: the
     * background is inset 3dp a side, so a 52dp cell shows a 46dp face.
     */
    static final int SPLIT_KEY_HEIGHT_DP = 52;
    static final int SPLIT_KEY_HEIGHT_LANDSCAPE_DP = 46;

    /** sw600dp — the standard "this is a large device" line. */
    static final int SPLIT_MIN_SMALLEST_WIDTH_DP = 600;

    /**
     * Combined width the two halves aim for before the gutter takes the rest.
     *
     * <p>560dp gives the ten-key rows 56dp cells. The remaining width is divided
     * between a central gutter and small outer insets, matching Samsung's
     * centred split clusters instead of stretching both halves to the bezels.
     *
     * <p>Kept as a fixed span rather than a share of the width, which is the
     * more obvious-looking model and the wrong one. Slack belongs in the middle:
     * the halves stay a constant, thumb-reachable size and the gap grows, which
     * is the entire point of splitting. A percentage gutter instead grows the
     * keys without limit — on a 2000dp display it would produce 179dp keys.
     */
    static final int SPLIT_TARGET_KEY_SPAN_DP = 560;
    static final int SPLIT_OUTER_INSET_DP = 28;
    static final int SPLIT_MIN_GUTTER_DP = 56;

    /** Rows that need to know where their halves divide. */
    enum Row { TOP, MIDDLE, BOTTOM_LETTERS, SYMBOLS, SYMBOLS_THIRD, KEYPAD }

    private KeyboardGeometry() {
    }

    /**
     * Key height, which depends on whether the keyboard split — not on the
     * screen height.
     *
     * <p>Height is useless as a signal here: an unfolded Fold is 835dp tall and
     * an ordinary phone is 832dp, so any rule keyed off it either scales both or
     * neither. Splitting already keys off {@code smallestScreenWidthDp}, which
     * is what actually separates the two (752dp against 384dp), so reusing that
     * decision costs nothing and adds no second threshold to reason about.
     */
    static int keyHeightDp(boolean landscape, boolean split) {
        if (split) {
            return landscape ? SPLIT_KEY_HEIGHT_LANDSCAPE_DP : SPLIT_KEY_HEIGHT_DP;
        }
        return landscape ? KEY_HEIGHT_LANDSCAPE_DP : KEY_HEIGHT_DP;
    }

    /**
     * Whether to split, from {@code smallestScreenWidthDp} — NOT screenWidthDp.
     *
     * <p>screenWidthDp is the current window width, so an ordinary phone turned
     * landscape reports roughly 730-900 and would split when nobody asked it to.
     * smallestScreenWidthDp is orientation-invariant: roughly 360-410 on phones,
     * roughly 320 on a folded cover screen, and 700+ on an unfolded inner
     * display, so only genuinely large devices cross the line.
     */
    static boolean shouldSplit(int smallestScreenWidthDp) {
        return smallestScreenWidthDp >= SPLIT_MIN_SMALLEST_WIDTH_DP;
    }

    /**
     * Whether to split, with the Settings switch (0.8.7, "Split keyboard on
     * large screens", on by default). Off keeps one full-width keyboard on the
     * Fold's inner screen; it never splits a phone or the cover screen.
     */
    static boolean shouldSplit(int smallestScreenWidthDp, boolean splitEnabled) {
        return splitEnabled && shouldSplit(smallestScreenWidthDp);
    }

    /**
     * Widest the nine-key pad gets: five columns of about 99dp. A 3x4 pad has
     * no two halves, so on a large screen it is centred at this width instead
     * of split, and in landscape it stops short of the bezels too.
     */
    static final int KEYPAD_MAX_WIDTH_DP = 480;

    /** Below this window width the pad simply fills the width, as on a phone held upright. */
    static final int KEYPAD_FULL_WIDTH_BELOW_DP = 600;

    /**
     * Width for the nine-key pad in dp, or -1 to fill the width. Fills on a
     * phone upright and on the Fold's cover screen; on a wider window (a phone
     * on its side, the unfolded inner screen) it keeps a thumb-sized width,
     * centred.
     */
    static int keypadWidthDp(int availableWidthDp, boolean split) {
        if (!split && availableWidthDp < KEYPAD_FULL_WIDTH_BELOW_DP) {
            return -1;
        }
        return Math.min(availableWidthDp, KEYPAD_MAX_WIDTH_DP);
    }

    /** Outer breathing room for each split cluster; phones remain edge-to-edge. */
    static int outerInsetDp(boolean split) {
        return split ? SPLIT_OUTER_INSET_DP : 0;
    }

    /**
     * Width of the gap between the halves. After reserving the fixed key span
     * and two outer insets, all remaining width belongs in the centre.
     *
     * <p>Deliberately unbounded above. There used to be a 320dp ceiling, and it
     * quietly defeated the whole model: past a 960dp window the gutter stopped
     * absorbing slack and every extra dp went into the keys instead, so a
     * 2000dp display produced 168dp keys. Capping the gap is the same mistake as
     * making it a percentage, just further out. A gap is allowed to be large —
     * that is what splitting a keyboard across a big screen means.
     */
    static int gutterDp(int screenWidthDp) {
        int reserved = SPLIT_TARGET_KEY_SPAN_DP + (2 * SPLIT_OUTER_INSET_DP);
        return Math.max(SPLIT_MIN_GUTTER_DP, screenWidthDp - reserved);
    }

    /**
     * How many keys sit left of the gutter, or -1 when the row should not split.
     *
     * <p>Splitting on the QWERTY seam (qwert|yuiop) rather than the arithmetic
     * middle is what makes the halves read as two halves of a keyboard.
     */
    static int splitIndex(Row row, int keyCount, boolean split) {
        if (!split || keyCount < 4) {
            return -1;
        }
        switch (row) {
            case TOP:
            case SYMBOLS:
                return keyCount / 2;               // qwert | yuiop
            case MIDDLE:
            case SYMBOLS_THIRD:
                return (keyCount + 1) / 2;         // asdfg | hjkl
            case BOTTOM_LETTERS:
                return (keyCount + 1) / 2;         // shift zxcv | bnm del
            case KEYPAD:
            default:
                return -1;                         // the 3x4 pad is centred, not split
        }
    }
}
