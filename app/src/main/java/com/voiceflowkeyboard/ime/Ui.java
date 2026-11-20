package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The app screens' building blocks in the B3.1 look: warm paper or navy,
 * 14 dp cards, tinted icon tiles, Inter.
 *
 * <p>The colour fields follow the system's light or dark setting. Every
 * Activity calls {@link #applyWindow} first in {@code onCreate}, on the main
 * thread, which sets them for that screen; Android recreates the Activity
 * when the setting changes.
 */
final class Ui {
    static int BACKGROUND;
    static int SURFACE;
    static int SURFACE_ALT;
    static int TEXT;
    static int MUTED;
    static int SUBTLE;
    static int DIVIDER;
    static int ACCENT;
    static int ACCENT_SOFT;
    static int DANGER;
    static int DANGER_SOFT;
    private static Palette palette;

    static {
        use(Palette.of(false));
    }

    /** Which icon tile colour a row gets. */
    enum Tile { ACCENT, OK, INDIGO, NEUTRAL, WARN }

    private Ui() {
    }

    private static void use(Palette p) {
        palette = p;
        BACKGROUND = p.bg;
        SURFACE = p.surface;
        SURFACE_ALT = p.raised;
        TEXT = p.fg;
        MUTED = p.muted;
        SUBTLE = p.subtle;
        DIVIDER = p.border;
        ACCENT = p.accent;
        ACCENT_SOFT = p.accentTint;
        DANGER = p.danger;
        DANGER_SOFT = p.dark ? 0xFF2B1517 : 0xFFFDECEA;
    }

    static Palette palette() {
        return palette;
    }

    @SuppressWarnings("deprecation")
    static void applyWindow(Activity activity) {
        use(Palette.from(activity));
        Window window = activity.getWindow();
        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);
        window.getDecorView().setBackgroundColor(BACKGROUND);
        int flags = window.getDecorView().getSystemUiVisibility();
        if (palette.dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @SuppressWarnings("deprecation")
    static void applySystemBarPadding(View view, int left, int top, int right, int bottom) {
        view.setPadding(left, top, right, bottom);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    left + insets.getSystemWindowInsetLeft(),
                    top + insets.getSystemWindowInsetTop(),
                    right + insets.getSystemWindowInsetRight(),
                    bottom + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        view.requestApplyInsets();
    }

    static GradientDrawable rounded(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable roundedStroke(Context context, int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = rounded(context, color, radiusDp);
        drawable.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        return drawable;
    }

    static TextView text(Context context, String value, int sp, boolean bold, int color) {
        return text(context, value, (float) sp, bold, color);
    }

    static TextView text(Context context, String value, float sp, boolean bold, int color) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setTypeface(bold ? Fonts.semibold(context) : Fonts.regular(context));
        text.setLineSpacing(0, 1.12f);
        return text;
    }

    /** A tonal button (the screen's one primary) or a quiet outlined one. */
    static TextView topAction(Context context, String text, boolean primary) {
        TextView action = text(context, text, 14, true, primary ? palette.accentInk : TEXT);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(dp(context, 44));
        action.setMinWidth(dp(context, 48));
        action.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        action.setBackground(primary
                ? roundedStroke(context, palette.accentTint, 22, palette.accentTintBorder)
                : roundedStroke(context, SURFACE, 22, palette.borderStrong));
        action.setClickable(true);
        action.setFocusable(true);
        return action;
    }

    /**
     * Sets a background without letting its padding (an inset drawable reports its
     * insets as padding) replace the padding the view already has.
     */
    static void setBackgroundKeepPadding(View view, Drawable background) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();
        view.setBackground(background);
        view.setPadding(left, top, right, bottom);
    }

    static View spacer(Context context, int heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, heightDp)
        ));
        return view;
    }

    /** A 14 dp card on the page background, with a hairline edge. */
    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedStroke(context, SURFACE, 14,
                palette.dark ? palette.border : Palette.withAlpha(palette.fg, 24)));
        card.setClipToOutline(true);
        return card;
    }

    static LinearLayout.LayoutParams cardParams(Context context, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(context, topMarginDp), 0, 0);
        return params;
    }

    /** A 32 dp rounded tile holding a tinted icon. */
    static View tile(Context context, int iconRes, Tile kind) {
        int fill;
        int ink;
        switch (kind) {
            case OK:
                fill = palette.tileOk;
                ink = palette.tileOkFg;
                break;
            case INDIGO:
                fill = palette.tileInd;
                ink = palette.tileIndFg;
                break;
            case WARN:
                fill = palette.tileWarn;
                ink = palette.tileWarnFg;
                break;
            case NEUTRAL:
                fill = palette.raised;
                ink = palette.muted;
                break;
            default:
                fill = palette.tileAcc;
                ink = palette.tileAccFg;
                break;
        }
        ImageView tile = new ImageView(context);
        tile.setImageResource(iconRes);
        tile.setColorFilter(ink);
        tile.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(context, 7);
        tile.setPadding(pad, pad, pad, pad);
        tile.setBackground(rounded(context, fill, 9));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return tile;
    }

    /** A settings row: tile, title, one line under it, and a chevron when it opens something. */
    static LinearLayout row(Context context, int iconRes, Tile kind, String title, String subtitle,
                            View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 64));
        row.setPadding(dp(context, 16), dp(context, 10), dp(context, 12), dp(context, 10));
        if (iconRes != 0) {
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32));
            tileParams.setMargins(0, 0, dp(context, 12), 0);
            row.addView(tile(context, iconRes, kind), tileParams);
        }
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(context, title, 15f, true, TEXT);
        labels.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(context, subtitle, 13f, false, SUBTLE);
            sub.setPadding(0, dp(context, 3), 0, 0);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setTag("subtitle");
            labels.addView(sub);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (listener != null) {
            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.ph_caret_right);
            chevron.setColorFilter(SUBTLE);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(ripple(context));
            row.setOnClickListener(listener);
        }
        return row;
    }

    /** A pressed-state ripple for rows inside a card. */
    static Drawable ripple(Context context) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(TEXT, 24)), null,
                new android.graphics.drawable.ColorDrawable(Color.WHITE));
    }

    static int withAlpha(int color, int alpha) {
        return Palette.withAlpha(color, alpha);
    }

    static View divider(Context context, int insetDp) {
        View divider = new View(context);
        divider.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(context, 1))
        );
        params.setMargins(dp(context, insetDp), 0, 0, 0);
        divider.setLayoutParams(params);
        return divider;
    }

    /** A small rounded label: "Up to date", "This phone", a style name. */
    static TextView badge(Context context, String value, Tile kind) {
        int fill;
        int ink;
        switch (kind) {
            case OK:
                fill = palette.tileOk;
                ink = palette.tileOkFg;
                break;
            case INDIGO:
                fill = palette.tileInd;
                ink = palette.tileIndFg;
                break;
            case WARN:
                fill = palette.tileWarn;
                ink = palette.tileWarnFg;
                break;
            case ACCENT:
                fill = palette.tileAcc;
                ink = palette.tileAccFg;
                break;
            default:
                fill = palette.raised;
                ink = palette.muted;
                break;
        }
        TextView badge = text(context, value, 11.5f, true, ink);
        badge.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
        badge.setBackground(rounded(context, fill, 999));
        badge.setSingleLine(true);
        return badge;
    }

    /** The app bar: Back (when there is somewhere to go back to) and the page title. */
    static LinearLayout header(Activity activity, String title, boolean back) {
        Context context = activity;
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(context, 60));
        bar.setPadding(dp(context, back ? 4 : 16), 0, dp(context, 8), 0);
        if (back) {
            ImageButton backButton = iconButton(context, R.drawable.ph_arrow_left, "Back", v -> activity.finish());
            backButton.setColorFilter(TEXT);
            bar.addView(backButton);
        }
        TextView titleView = text(context, title, 22f, true, TEXT);
        titleView.setLetterSpacing(-0.02f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            titleView.setAccessibilityHeading(true);
        }
        titleView.setPadding(dp(context, back ? 6 : 0), 0, 0, 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    static ImageButton iconButton(Context context, int iconRes, String description, View.OnClickListener listener) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(iconRes);
        button.setColorFilter(MUTED);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(TEXT, 30)), null, mask));
        int pad = dp(context, 12);
        button.setPadding(pad, pad, pad, pad);
        button.setContentDescription(description);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        return button;
    }

    /** Small section label over a card ("Voice input", "Updates"). */
    static TextView sectionLabel(Context context, String value) {
        TextView label = text(context, value, 13f, true, MUTED);
        label.setPadding(dp(context, 4), dp(context, 20), 0, dp(context, 8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            label.setAccessibilityHeading(true);
        }
        return label;
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
