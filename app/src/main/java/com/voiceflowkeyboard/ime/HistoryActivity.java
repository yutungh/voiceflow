package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * History in the app: one card per dictation, newest first, grouped by day, with
 * its style, time, Copy, Original and Open. The same 20 dictations the keyboard
 * keeps; nothing new is stored.
 */
public class HistoryActivity extends Activity {
    private final Set<String> showingOriginal = new HashSet<>();
    private final Set<String> expanded = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyWindow(this);
        setTitle("History");
    }

    @Override
    protected void onResume() {
        super.onResume();
        setContentView(buildContent());
    }

    private View buildContent() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Ui.BACKGROUND);
        Ui.applySystemBarPadding(screen, 0, 0, 0, 0);
        screen.addView(Ui.header(this, "History", true));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(28));
        scroll.addView(list);
        screen.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = getResources().getConfiguration().screenWidthDp;
        if (width > 640) {
            int side = dp((width - 600) / 2);
            list.setPadding(side, 0, side, dp(28));
        }

        List<VoiceHistoryItem> history = Prefs.transcriptHistory(this);
        if (history.isEmpty()) {
            TextView empty = Ui.text(this, "Nothing dictated yet. Your last 20 dictations appear here, "
                    + "with their original words.", 14f, false, Ui.SUBTLE);
            empty.setPadding(dp(4), dp(12), dp(4), 0);
            list.addView(empty);
            return screen;
        }
        String lastDay = "";
        for (VoiceHistoryItem item : history) {
            String day = dayLabel(item.timestampMs);
            if (!day.equals(lastDay)) {
                TextView label = Ui.sectionLabel(this, day);
                label.setPadding(dp(4), dp(lastDay.isEmpty() ? 4 : 18), 0, dp(8));
                list.addView(label);
                lastDay = day;
            }
            list.addView(card(item), Ui.cardParams(this, 0));
            list.addView(Ui.spacer(this, 10));
        }
        return screen;
    }

    private View card(VoiceHistoryItem item) {
        LinearLayout card = Ui.card(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int icon = item.isTranslation() ? R.drawable.ph_translate
                : item.isCreation() ? R.drawable.ph_pencil_simple_line : R.drawable.ph_microphone;
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        tileParams.setMargins(0, 0, dp(8), 0);
        View tile = Ui.tile(this, icon, Ui.Tile.INDIGO);
        tile.setPadding(dp(4), dp(4), dp(4), dp(4));
        top.addView(tile, tileParams);
        String kind = item.isTranslation()
                ? "Translation" + (item.targetLanguage.isEmpty() ? "" : " · " + shortLanguage(item.targetLanguage))
                : item.isCreation() ? "Created" : "Dictation";
        top.addView(Ui.text(this, kind, 13f, true, Ui.TEXT));
        if (!Prefs.PRESET_RAW.equals(item.preset)) {
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pillParams.setMargins(dp(8), 0, 0, 0);
            top.addView(Ui.badge(this, VoiceBar.styleLabel(Prefs.labelForPreset(this, item.preset), item.expression),
                    Ui.Tile.NEUTRAL), pillParams);
        }
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView time = Ui.text(this, item.timestampMs > 0
                ? DateFormat.getTimeFormat(this).format(item.timestampMs) : "", 12.5f, false, Ui.SUBTLE);
        top.addView(time);
        card.addView(top);

        boolean hasOutput = item.hasOutputForVariant(item.preset, item.expression);
        boolean original = !hasOutput || showingOriginal.contains(item.id);
        String visible = original ? item.rawText : item.outputForVariant(item.preset, item.expression);
        boolean open = expanded.contains(item.id);
        TextView text = Ui.text(this, visible == null || visible.trim().isEmpty() ? "(empty)" : visible.trim(),
                15f, false, open ? Ui.TEXT : Ui.MUTED);
        text.setLineSpacing(0, 1.3f);
        text.setPadding(0, dp(10), 0, dp(6));
        if (!open) {
            text.setMaxLines(3);
            text.setEllipsize(TextUtils.TruncateAt.END);
        }
        text.setTextIsSelectable(open);
        text.setOnClickListener(v -> {
            if (!expanded.remove(item.id)) {
                expanded.add(item.id);
            }
            setContentView(buildContent());
        });
        if (original && hasOutput) {
            TextView label = Ui.text(this, item.isTranslation() ? "Original source" : "Your original words",
                    12f, true, Ui.SUBTLE);
            label.setPadding(0, dp(10), 0, 0);
            card.addView(label);
            text.setPadding(0, dp(4), 0, dp(6));
        }
        card.addView(text);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(action("Copy", R.drawable.ph_copy, false, v -> copy(visible)));
        if (hasOutput && !Prefs.PRESET_RAW.equals(item.preset)) {
            actions.addView(action(original ? "Cleaned" : "Original", original ? R.drawable.ph_sparkle : R.drawable.ph_quotes,
                    false, v -> {
                        if (!showingOriginal.remove(item.id)) {
                            showingOriginal.add(item.id);
                        }
                        setContentView(buildContent());
                    }));
        }
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        actions.addView(action("Open", R.drawable.ph_arrows_out_simple, true, v -> {
            Intent intent = new Intent(this, TranscriptDetailActivity.class);
            intent.putExtra(TranscriptDetailActivity.EXTRA_HISTORY_ID, item.id);
            startActivity(intent);
        }));
        card.addView(actions);
        return card;
    }

    private TextView action(String label, int iconRes, boolean primary, View.OnClickListener listener) {
        TextView button = Ui.text(this, label, 13f, true, primary ? Ui.palette().accentInk : Ui.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), 0, dp(14), 0);
        android.graphics.drawable.Drawable icon = getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setBounds(0, 0, dp(16), dp(16));
            icon.setTint(primary ? Ui.palette().accentInk : Ui.MUTED);
            button.setCompoundDrawables(icon, null, null, null);
            button.setCompoundDrawablePadding(dp(6));
        }
        android.graphics.drawable.GradientDrawable shape = primary
                ? Ui.roundedStroke(this, Ui.palette().accentTint, 999, Ui.palette().accentTintBorder)
                : Ui.roundedStroke(this, Ui.SURFACE, 999, Ui.palette().borderStrong);
        Ui.setBackgroundKeepPadding(button, new android.graphics.drawable.InsetDrawable(shape, 0, dp(7), 0, dp(7)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(8), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null && text != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("VoiceFlow", text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private String dayLabel(long timestampMs) {
        if (timestampMs <= 0) {
            return "Earlier";
        }
        if (DateUtils.isToday(timestampMs)) {
            return "Today";
        }
        if (DateUtils.isToday(timestampMs + DateUtils.DAY_IN_MILLIS)) {
            return "Yesterday";
        }
        return DateFormat.format("EEEE, MMM d", timestampMs).toString();
    }

    private static String shortLanguage(String language) {
        int qualifier = language.indexOf(" (");
        return qualifier > 0 ? language.substring(0, qualifier) : language;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
