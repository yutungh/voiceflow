package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Inter (SIL OFL 1.1, assets/licenses/Inter-OFL.txt), bundled so the app and
 * the keyboard read the same on every phone. Falls back to the system font if
 * a file cannot be read, so text never disappears.
 */
final class Fonts {
    private static volatile Typeface regular;
    private static volatile Typeface medium;
    private static volatile Typeface semibold;

    private Fonts() {
    }

    static Typeface regular(Context context) {
        Typeface face = regular;
        if (face == null) {
            face = load(context, R.font.inter_regular, Typeface.DEFAULT);
            regular = face;
        }
        return face;
    }

    static Typeface medium(Context context) {
        Typeface face = medium;
        if (face == null) {
            face = load(context, R.font.inter_medium, Typeface.DEFAULT);
            medium = face;
        }
        return face;
    }

    static Typeface semibold(Context context) {
        Typeface face = semibold;
        if (face == null) {
            face = load(context, R.font.inter_semibold, Typeface.DEFAULT_BOLD);
            semibold = face;
        }
        return face;
    }

    private static Typeface load(Context context, int res, Typeface fallback) {
        try {
            Typeface face = context.getResources().getFont(res);
            return face == null ? fallback : face;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
