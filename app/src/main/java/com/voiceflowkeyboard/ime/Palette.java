package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/**
 * The one set of colours the app and the keyboard share: desktop B3.1 adapted
 * to Android (warm paper in light, navy and mint in dark). It follows the
 * system's light or dark setting.
 *
 * <p>Rules that come with it, as on desktop: one tonal primary per surface;
 * record red, success green and suggestion indigo only ever beside words.
 */
final class Palette {
    final boolean dark;
    /** App window background. */
    final int bg;
    /** Keyboard background. */
    final int ime;
    final int rail;
    final int surface;
    final int raised;
    final int border;
    final int borderStrong;
    final int fg;
    final int muted;
    final int subtle;
    final int accent;
    final int onAccent;
    final int accentInk;
    final int accentTint;
    final int accentTintBorder;
    final int tileAcc;
    final int tileAccFg;
    final int tileOk;
    final int tileOkFg;
    final int tileInd;
    final int tileIndFg;
    final int tileWarn;
    final int tileWarnFg;
    final int success;
    final int info;
    final int record;
    final int danger;
    final int wave;
    final int key;
    final int keyFn;
    final int keyFg;
    final int keyShadow;
    final int highlight;
    final int scrim;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF071016;
            ime = 0xFF0A151C;
            rail = 0xFF0A151C;
            surface = 0xFF0D1A22;
            raised = 0xFF122530;
            border = 0xFF1A2F38;
            borderStrong = 0xFF294955;
            fg = 0xFFE4EDEF;
            muted = 0xFFA9BBC1;
            subtle = 0xFF8CA2AA;
            accent = 0xFF2DD4BF;
            onAccent = 0xFF042420;
            accentInk = 0xFF5EEAD4;
            accentTint = 0xFF0F2F2E;
            accentTintBorder = 0xFF2E7A73;
            tileAcc = 0xFF10302F;
            tileAccFg = 0xFF5EEAD4;
            tileOk = 0xFF0F2A22;
            tileOkFg = 0xFF6EE7B7;
            tileInd = 0xFF1A1C3B;
            tileIndFg = 0xFFB0B1FB;
            tileWarn = 0xFF2A2110;
            tileWarnFg = 0xFFF5B544;
            success = 0xFF6EE7B7;
            info = 0xFFB0B1FB;
            record = 0xFFFF5A4E;
            danger = 0xFFFF8A7A;
            wave = 0xFF2DD4BF;
            key = 0xFF172A34;
            keyFn = 0xFF0F2029;
            keyFg = 0xFFE4EDEF;
            keyShadow = 0x80000000;
            highlight = 0xFF3A3015;
            scrim = 0x9E02080C;
        } else {
            bg = 0xFFF7F6F3;
            ime = 0xFFEAE7E1;
            rail = 0xFFEFEDE8;
            surface = 0xFFFFFFFF;
            raised = 0xFFF1EFEB;
            border = 0xFFE6E3DD;
            borderStrong = 0xFFD3CFC7;
            fg = 0xFF1D1C1A;
            muted = 0xFF4A4640;
            subtle = 0xFF66615A;
            accent = 0xFF115E59;
            onAccent = 0xFFFFFFFF;
            accentInk = 0xFF0E4F4B;
            accentTint = 0xFFE2EEEC;
            accentTintBorder = 0xFF5F918B;
            tileAcc = 0xFFE2EEEC;
            tileAccFg = 0xFF115E59;
            tileOk = 0xFFE2F0E8;
            tileOkFg = 0xFF2D7350;
            tileInd = 0xFFE9E9F4;
            tileIndFg = 0xFF4A4E86;
            tileWarn = 0xFFF5ECD9;
            tileWarnFg = 0xFF87590B;
            success = 0xFF2D7350;
            info = 0xFF4A4E86;
            record = 0xFFD23F2B;
            danger = 0xFF9A3426;
            wave = 0xFF115E59;
            key = 0xFFFFFFFF;
            keyFn = 0xFFE2DED6;
            keyFg = 0xFF1D1C1A;
            keyShadow = 0x291D1C1A;
            highlight = 0xFFFCEFC7;
            scrim = 0x521D1C1A;
        }
    }

    private static final Palette LIGHT = new Palette(false);
    private static final Palette DARK = new Palette(true);

    static Palette of(boolean dark) {
        return dark ? DARK : LIGHT;
    }

    static Palette from(Context context) {
        return of(isNight(context.getResources().getConfiguration()));
    }

    static boolean isNight(Configuration configuration) {
        return (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The colour with a new alpha (0-255). */
    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** WCAG 2 contrast ratio between two opaque colours. */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xff)
                + 0.7152 * channel((color >> 8) & 0xff)
                + 0.0722 * channel(color & 0xff);
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
