package com.voiceflowkeyboard.ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Word lists: the names and words VoiceFlow spells the user's way. An entry whose heard
 * forms differ from its spelling is "Always fix" (a correction); one that is only
 * its own spelling is a "Hint" (it helps transcription hear it).
 *
 * <p>This phone's own rows are edited here, every edit saved as it is made. Words
 * from the sync server (household, from the user's notes, the user's own list and edits made on other
 * devices) are shown read-only with where they come from; they are changed where
 * they are kept, and reach the phone on its next sync.
 */
public class FindReplaceActivity extends Activity {
    private static final int FILTER_ALL = 0;
    private static final int FILTER_FIX = 1;
    private static final int FILTER_HINTS = 2;
    /** Rows drawn at most at once; search finds the rest. */
    private static final int MAX_ROWS = 200;

    private final List<PhraseReplacement> replacements = new ArrayList<>();
    /** Read-only entries from the sync server, in display order. */
    private final List<SharedRow> shared = new ArrayList<>();
    private WordLists.View view;
    private LinearLayout list;
    private LinearLayout segments;
    private EditText search;
    private int filter = FILTER_ALL;

    /** One read-only entry from the sync server's lists. */
    static final class SharedRow {
        final String spelling;
        final List<String> heard;
        final boolean hint;
        final String layer;
        final String gloss;

        SharedRow(String spelling, List<String> heard, boolean hint, String layer, String gloss) {
            this.spelling = spelling;
            this.heard = heard;
            this.hint = hint;
            this.layer = layer;
            this.gloss = gloss == null ? "" : gloss;
        }
    }

    /** The sync server's entries of a snapshot: words as hints, corrections as fixes. The phone's own and built-in rows are left out. */
    static List<SharedRow> sharedRows(WordMerge.Snapshot snapshot) {
        List<SharedRow> rows = new ArrayList<>();
        if (snapshot == null) {
            return rows;
        }
        for (WordLayer.Correction c : snapshot.corrections) {
            if (fromNas(c.layer)) {
                rows.add(new SharedRow(c.to, c.from, false, c.layer, ""));
            }
        }
        for (WordLayer.Term t : snapshot.terms) {
            if (fromNas(t.layer)) {
                rows.add(new SharedRow(t.text, t.heardAs, true, t.layer, t.gloss));
            }
        }
        java.util.Collections.sort(rows, (a, b) -> {
            int c = a.spelling.compareToIgnoreCase(b.spelling);
            return c != 0 ? c : Boolean.compare(a.hint, b.hint);
        });
        return rows;
    }

    private static boolean fromNas(String layer) {
        return !WordLayer.PHONE.equals(layer) && !WordLayer.DEFAULTS.equals(layer);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyWindow(this);
        setTitle("Word lists");
        replacements.addAll(Prefs.userPhraseReplacements(this));
        loadShared();
        setContentView(buildContent());
    }

    private void loadShared() {
        shared.clear();
        try {
            view = WordLists.current(this);
            shared.addAll(sharedRows(view.snapshot));
        } catch (RuntimeException e) {
            view = null;
        }
    }

    /** One line on where the sync server's words stand, or null when this phone is not enrolled. */
    private String syncSummary() {
        if (view == null || SyncStore.enrolment(this) == null) {
            return null;
        }
        WordLists.State state = view.state;
        if (view.snapshot == null) {
            return state.lastAttemptAt > 0 && !state.reachable
                    ? "Your word lists: not reached yet. This phone's words work as before."
                    : "Your word lists arrive with the next sync.";
        }
        int words = 0;
        int fixes = 0;
        for (SharedRow row : shared) {
            if (row.hint) {
                words++;
            } else {
                fixes++;
            }
        }
        String counts = words + (words == 1 ? " word" : " words")
                + (fixes > 0 ? " and " + fixes + (fixes == 1 ? " fix" : " fixes") : "") + " from your lists";
        String when = state.lastSyncAt > 0 ? "synced " + ago(System.currentTimeMillis() - state.lastSyncAt) : "";
        if (!state.reachable) {
            return counts + ". Offline: using the copy on this phone" + (when.isEmpty() ? "." : ", " + when + ".");
        }
        return counts + (when.isEmpty() ? "." : ", " + when + ".");
    }

    static String ago(long ms) {
        long minutes = Math.max(0, ms / 60_000L);
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        if (hours < 48) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        return (hours / 24) + " days ago";
    }

    private View buildContent() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BACKGROUND);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        Ui.applySystemBarPadding(frame, 0, 0, 0, 0);
        screen.addView(Ui.header(this, "Word lists", true));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int width = getResources().getConfiguration().screenWidthDp;
        int side = width > 640 ? dp((width - 600) / 2) : dp(16);
        root.setPadding(side, 0, side, dp(96));
        scroll.addView(root);
        screen.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        String summary = syncSummary();
        TextView note = Ui.text(this, "Names and words spelled your way. Always fix rewrites what it hears; "
                + "a hint helps it hear the word. "
                + (summary == null ? "Kept on this phone." : "You edit this phone's words here; words from your lists are changed where they're kept."),
                13f, false, Ui.SUBTLE);
        note.setPadding(dp(4), 0, dp(4), dp(summary == null ? 12 : 6));
        root.addView(note);
        if (summary != null) {
            TextView sync = Ui.text(this, summary, 13f, true, Ui.MUTED);
            sync.setPadding(dp(4), 0, dp(4), dp(12));
            root.addView(sync);
        }

        int total = replacements.size() + shared.size();
        search = new EditText(this);
        search.setHint("Search " + total + (total == 1 ? " word" : " words"));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.SUBTLE);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setTypeface(Fonts.regular(this));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setMinHeight(dp(48));
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 10, Ui.palette().borderStrong));
        android.graphics.drawable.Drawable glass = getDrawable(R.drawable.ph_magnifying_glass);
        if (glass != null) {
            glass = glass.mutate();
            glass.setBounds(0, 0, dp(18), dp(18));
            glass.setTint(Ui.SUBTLE);
            search.setCompoundDrawables(glass, null, null, null);
            search.setCompoundDrawablePadding(dp(10));
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderList();
            }
        });
        root.addView(search, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        segments = new LinearLayout(this);
        segments.setOrientation(LinearLayout.HORIZONTAL);
        segments.setPadding(dp(3), dp(3), dp(3), dp(3));
        segments.setBackground(Ui.roundedStroke(this, Ui.SURFACE_ALT, 10, Ui.DIVIDER));
        root.addView(segments, Ui.cardParams(this, 12));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, Ui.cardParams(this, 12));
        renderList();
        frame.addView(screen);

        TextView add = Ui.topAction(this, "Add a word", true);
        add.setTextSize(15);
        add.setMinHeight(dp(52));
        android.graphics.drawable.Drawable plus = getDrawable(R.drawable.ph_plus);
        if (plus != null) {
            plus = plus.mutate();
            plus.setBounds(0, 0, dp(18), dp(18));
            plus.setTint(Ui.palette().accentInk);
            add.setCompoundDrawables(plus, null, null, null);
            add.setCompoundDrawablePadding(dp(8));
        }
        add.setBackground(Ui.roundedStroke(this, Ui.palette().accentTint, 16, Ui.palette().accentTintBorder));
        add.setElevation(dp(4));
        add.setOnClickListener(v -> showEditor(-1));
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        addParams.setMargins(0, 0, dp(16), dp(18));
        frame.addView(add, addParams);
        return frame;
    }

    private static boolean isHint(PhraseReplacement replacement) {
        List<String> heard = replacement.heardForms();
        return heard.isEmpty() || (heard.size() == 1 && heard.get(0).equalsIgnoreCase(replacement.to.trim()));
    }

    private void renderSegments() {
        segments.removeAllViews();
        int fixes = 0;
        for (PhraseReplacement replacement : replacements) {
            if (!isHint(replacement)) {
                fixes++;
            }
        }
        int hints = replacements.size() - fixes;
        for (SharedRow row : shared) {
            if (row.hint) {
                hints++;
            } else {
                fixes++;
            }
        }
        String[] labels = {"All", "Always fix  " + fixes, "Hints  " + hints};
        for (int i = 0; i < labels.length; i++) {
            final int value = i;
            boolean on = filter == i;
            TextView segment = Ui.text(this, labels[i], 13f, on, on ? Ui.TEXT : Ui.MUTED);
            segment.setGravity(Gravity.CENTER);
            segment.setMinHeight(dp(42));
            segment.setBackground(on ? Ui.roundedStroke(this, Ui.SURFACE, 8, Ui.DIVIDER) : null);
            segment.setContentDescription(labels[i].replace("  ", ", ") + (on ? ", shown" : ""));
            segment.setOnClickListener(v -> {
                filter = value;
                renderList();
            });
            segments.addView(segment, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private void renderList() {
        renderSegments();
        list.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        LinearLayout card = Ui.card(this);
        int shown = 0;
        for (int i = 0; i < replacements.size(); i++) {
            PhraseReplacement replacement = replacements.get(i);
            boolean hint = isHint(replacement);
            if ((filter == FILTER_FIX && hint) || (filter == FILTER_HINTS && !hint)) {
                continue;
            }
            if (!query.isEmpty()
                    && !replacement.to.toLowerCase(Locale.ROOT).contains(query)
                    && !replacement.from.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            if (shown > 0) {
                card.addView(Ui.divider(this, 16));
            }
            final int index = i;
            card.addView(wordRow(replacement, hint, v -> showEditor(index)));
            shown++;
        }
        int hidden = 0;
        for (SharedRow row : shared) {
            if ((filter == FILTER_FIX && row.hint) || (filter == FILTER_HINTS && !row.hint)) {
                continue;
            }
            if (!query.isEmpty() && !matches(row, query)) {
                continue;
            }
            if (shown >= MAX_ROWS) {
                hidden++;
                continue;
            }
            if (shown > 0) {
                card.addView(Ui.divider(this, 16));
            }
            card.addView(sharedRow(row));
            shown++;
        }
        if (hidden > 0) {
            TextView more = Ui.text(this, hidden + " more. Search to find them.", 13f, false, Ui.SUBTLE);
            more.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.addView(Ui.divider(this, 16));
            card.addView(more);
        }
        if (shown == 0) {
            TextView empty = Ui.text(this, replacements.isEmpty() && shared.isEmpty()
                    ? "No words yet. Add a name or a word VoiceFlow should spell your way."
                    : "No words match.", 14f, false, Ui.SUBTLE);
            empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            card.addView(empty);
        }
        list.addView(card);
    }

    private View wordRow(PhraseReplacement replacement, boolean hint, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(10), dp(14), dp(10));
        row.setClickable(true);
        row.setBackground(Ui.ripple(this));
        row.setOnClickListener(listener);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, replacement.to.trim(), 15.5f, true, Ui.TEXT));
        String detail = hint
                ? "Hint only: helps it hear the word"
                : "Always written for " + String.join(", ", replacement.heardForms());
        TextView sub = Ui.text(this, detail, 13f, false, Ui.SUBTLE);
        sub.setPadding(0, dp(3), dp(8), 0);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(sub);
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.badge(this, "This phone", Ui.Tile.NEUTRAL));
        row.setContentDescription(replacement.to.trim() + ". " + detail + ". Edit");
        return row;
    }

    private static boolean matches(SharedRow row, String query) {
        if (row.spelling.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        for (String form : row.heard) {
            if (form.toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    static String sharedDetail(SharedRow row) {
        if (!row.hint) {
            return "Always written for " + String.join(", ", row.heard);
        }
        return row.heard.isEmpty()
                ? "Hint only: helps it hear the word"
                : "Hint: helps it hear the word, not " + String.join(" or ", row.heard);
    }

    private View sharedRow(SharedRow row) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(64));
        line.setPadding(dp(16), dp(10), dp(14), dp(10));
        line.setClickable(true);
        line.setBackground(Ui.ripple(this));
        line.setOnClickListener(v -> showShared(row));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(Ui.text(this, row.spelling, 15.5f, true, Ui.TEXT));
        String detail = sharedDetail(row);
        TextView sub = Ui.text(this, detail, 13f, false, Ui.SUBTLE);
        sub.setPadding(0, dp(3), dp(8), 0);
        sub.setMaxLines(2);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(sub);
        line.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String badge = WordLists.badge(row.layer);
        line.addView(Ui.badge(this, badge, WordLayer.GENERATED.equals(row.layer) ? Ui.Tile.INDIGO
                : WordLayer.HOUSEHOLD.equals(row.layer) ? Ui.Tile.OK : Ui.Tile.ACCENT));
        line.setContentDescription(row.spelling + ". " + detail + ". " + badge + ". Details");
        return line;
    }

    private void showShared(SharedRow row) {
        StringBuilder text = new StringBuilder(sharedDetail(row)).append('.');
        if (!row.gloss.isEmpty()) {
            text.append("\n\n").append(row.gloss);
        }
        String where;
        switch (row.layer) {
            case WordLayer.GENERATED:
                where = "It comes from your notes, through your synced word lists.";
                break;
            case WordLayer.HOUSEHOLD:
                where = "It is on the household list, shared with everyone at home.";
                break;
            case WordLayer.EDIT:
                where = "It was added in VoiceFlow on one of your devices.";
                break;
            default:
                where = "It is on your own synced list.";
        }
        text.append("\n\n").append(where).append(" Change it there; the phone picks it up on its next sync.");
        new AlertDialog.Builder(this)
                .setTitle(row.spelling)
                .setMessage(text.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showEditor(int index) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), 0);

        EditText to = input("Kestrel", false);
        EditText from = input("Castrel\nKestral", true);
        EditText context = input("Optional: Kestrel is the name of our project.", true);
        if (index >= 0) {
            from.setText(replacements.get(index).from);
            to.setText(replacements.get(index).to);
            context.setText(replacements.get(index).context);
        }
        fields.addView(label("Write it as"));
        fields.addView(to);
        fields.addView(label("Heard as, one per line (the same word makes it a hint)"));
        fields.addView(from);
        fields.addView(label("Context for transcription (optional)"));
        fields.addView(context);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "Edit word" : "Add a word")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String spelling = to.getText().toString();
                    String heard = from.getText().toString();
                    if (spelling.trim().isEmpty()) {
                        return;
                    }
                    // A word with no heard forms is a hint for its own spelling.
                    PhraseReplacement replacement = new PhraseReplacement(
                            heard.trim().isEmpty() ? spelling.trim() : heard,
                            spelling,
                            context.getText().toString()
                    );
                    if (index >= 0) {
                        replacements.set(index, replacement);
                    } else {
                        replacements.add(replacement);
                    }
                    save();
                });
        if (index >= 0) {
            builder.setNeutralButton("Delete", (dialog, which) -> {
                replacements.remove(index);
                save();
            });
        }
        builder.show();
    }

    private void save() {
        Prefs.saveUserPhraseReplacements(this, replacements);
        loadShared();
        setContentView(buildContent());
    }

    private TextView label(String value) {
        TextView label = Ui.text(this, value, 13f, true, Ui.MUTED);
        label.setPadding(0, dp(14), 0, dp(6));
        return label;
    }

    private EditText input(String hint, boolean multiline) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.SUBTLE);
        input.setSingleLine(!multiline);
        input.setMinLines(multiline ? 2 : 1);
        input.setMaxLines(multiline ? 4 : 1);
        input.setMinHeight(dp(48));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(Ui.roundedStroke(this, Ui.SURFACE, 10, Ui.palette().borderStrong));
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0)
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return input;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
