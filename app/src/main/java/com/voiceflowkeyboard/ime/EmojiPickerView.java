package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The picker owns its search editor and keys; queries never use the app's InputConnection. */
final class EmojiPickerView extends LinearLayout {
    interface Listener {
        boolean onEmoji(String emoji);
        void onClose();
        void onDelete();
    }

    private static final String RECENT = "Recent";
    private static final int RECENT_LIMIT = 48;
    private final Listener listener;
    private final int textColor;
    private final int keyColor;
    private final int accentColor;
    private final int keyHeightDp;
    private final boolean rememberRecent;
    private final SharedPreferences preferences;
    private final List<String> recent = new ArrayList<>();
    private final List<EmojiCatalog.Entry> visible = new ArrayList<>();
    private final EditText search;
    private final TextView delete;
    private final LinearLayout tabs;
    private final HorizontalScrollView tabScroll;
    private final GridView grid;
    private final TextView empty;
    private final TextView hint;
    private final LinearLayout searchKeys;
    private final EmojiAdapter adapter = new EmojiAdapter();
    private EmojiCatalog catalog;
    private String category;
    private boolean searching;
    private boolean numbers;
    private PopupWindow variantPopup;

    EmojiPickerView(Context context, int background, int textColor, int keyColor,
                    int accentColor, int keyHeightDp, boolean rememberRecent, Listener listener) {
        super(context);
        this.listener = listener;
        this.textColor = textColor;
        this.keyColor = keyColor;
        this.accentColor = accentColor;
        this.keyHeightDp = keyHeightDp;
        this.rememberRecent = rememberRecent;
        preferences = context.getSharedPreferences("emoji_picker", Context.MODE_PRIVATE);
        for (String emoji : preferences.getString("recent", "").split("\n")) {
            if (!emoji.isEmpty() && !recent.contains(emoji) && recent.size() < RECENT_LIMIT) recent.add(emoji);
        }
        setOrientation(VERTICAL);
        setBackgroundColor(background);
        setPadding(dp(2), 0, dp(2), 0);

        LinearLayout header = row();
        TextView close = button("ABC", "Return to keyboard", () -> listener.onClose());
        header.addView(close, new LayoutParams(dp(48), dp(44)));
        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(textColor);
        search.setHintTextColor(textColor);
        search.setHint("Search emojis");
        search.setContentDescription("Search emojis");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_SEARCH);
        search.setShowSoftInputOnFocus(false);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        search.setBackground(shape(keyColor));
        search.setPadding(dp(12), 0, dp(8), 0);
        search.setOnClickListener(v -> setSearching(true));
        search.setOnFocusChangeListener((v, focused) -> { if (focused) setSearching(true); });
        header.addView(search, new LayoutParams(0, dp(40), 1));
        header.addView(button("×", "Clear emoji search", () -> search.setText("")),
                new LayoutParams(dp(40), dp(44)));
        delete = button("⌫", "Delete from message", () -> {
            if (searching) deleteQuery();
            else listener.onDelete();
        });
        header.addView(delete, new LayoutParams(dp(44), dp(44)));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));

        tabs = row();
        tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.addView(tabs);
        addView(tabScroll, new LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));

        FrameLayout results = new FrameLayout(context);
        grid = new GridView(context);
        grid.setColumnWidth(dp(48));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(2));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> choose(visible.get(position), view));
        grid.setOnItemLongClickListener((parent, view, position, id) -> showVariants(visible.get(position), view));
        results.addView(grid, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        empty = label("Loading emojis…", 14);
        results.addView(empty, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        grid.setEmptyView(empty);
        addView(results, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        hint = label("Hold an emoji for skin tones", 11);
        hint.setAlpha(0.65f);
        addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, dp(24)));
        searchKeys = new LinearLayout(context);
        searchKeys.setOrientation(VERTICAL);
        searchKeys.setVisibility(GONE);
        addView(searchKeys, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        buildSearchKeys();
        // Keep the search field unfocused until it is deliberately tapped.
        setFocusableInTouchMode(true);
        requestFocus();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    void setCatalog(EmojiCatalog catalog) {
        this.catalog = catalog;
        category = recent.isEmpty() ? catalog.categories().get(0) : RECENT;
        buildTabs();
        refresh();
    }

    void showLoadError() {
        empty.setText("Couldn’t load emojis. Tap ABC and try again.");
    }

    boolean handleBack() {
        if (variantPopup != null && variantPopup.isShowing()) {
            variantPopup.dismiss();
        } else if (searching) {
            setSearching(false);
        } else {
            listener.onClose();
        }
        return true;
    }

    boolean handleHardwareKey(int keyCode, KeyEvent event) {
        if (!searching || event.isSystem()) return false;
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            deleteQuery();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            setSearching(false);
            return true;
        }
        int unicode = event.getUnicodeChar();
        if (unicode > 0 && Character.isValidCodePoint(unicode)
                && !Character.isISOControl(unicode) && !event.isCtrlPressed() && !event.isAltPressed()) {
            insertQuery(new String(Character.toChars(unicode)));
            return true;
        }
        // Never let search navigation/editing keystrokes reach the message.
        return search.onKeyDown(keyCode, event) || keyCode != KeyEvent.KEYCODE_BACK;
    }

    boolean isSearching() { return searching; }

    private void buildTabs() {
        tabs.removeAllViews();
        List<String> names = new ArrayList<>();
        names.add(RECENT);
        names.addAll(catalog.categories());
        for (String name : names) {
            TextView tab = button(shortCategory(name), name, () -> {
                category = name;
                search.setText("");
                setSearching(false);
                buildTabs();
                refresh();
            });
            tab.setPadding(dp(12), 0, dp(12), 0);
            tab.setTextColor(name.equals(category) ? accentColor : textColor);
            tab.setSelected(name.equals(category));
            tab.setTypeface(name.equals(category) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabs.addView(tab, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)));
        }
    }

    private String shortCategory(String name) {
        switch (name) {
            case "Smileys & Emotion": return "Smileys";
            case "People & Body": return "People";
            case "Animals & Nature": return "Nature";
            case "Food & Drink": return "Food";
            case "Travel & Places": return "Travel";
            default: return name;
        }
    }

    private void refresh() {
        if (catalog == null) return;
        visible.clear();
        if (search.length() == 0 && RECENT.equals(category)) {
            for (String emoji : recent) {
                EmojiCatalog.Entry entry = catalog.find(emoji);
                if (entry != null) visible.add(entry);
            }
            empty.setText("Your recent emojis will appear here");
        } else {
            visible.addAll(catalog.search(search.getText().toString(), category));
            empty.setText("No emojis found. Try another word.");
        }
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
    }

    private void choose(EmojiCatalog.Entry entry, View view) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (listener.onEmoji(entry.emoji) && rememberRecent) {
            recent.remove(entry.emoji);
            recent.add(0, entry.emoji);
            while (recent.size() > RECENT_LIMIT) recent.remove(recent.size() - 1);
            preferences.edit().putString("recent", String.join("\n", recent)).apply();
        }
        // Keep the current grid stable while the user is tapping several emojis.
    }

    private boolean showVariants(EmojiCatalog.Entry entry, View anchor) {
        List<EmojiCatalog.Entry> variants = catalog.variants(entry);
        if (variants.size() < 2) return false;
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        content.setBackground(shape(keyColor));
        TextView title = label("Choose skin tone", 13);
        content.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, dp(28)));
        GridView choices = new GridView(getContext());
        choices.setNumColumns(6);
        choices.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return variants.size(); }
            @Override public Object getItem(int position) { return variants.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return emojiCell(variants.get(position), convertView);
            }
        });
        content.addView(choices, new LayoutParams(LayoutParams.MATCH_PARENT,
                dp(Math.min(4, (variants.size() + 5) / 6) * 48)));
        variantPopup = new PopupWindow(content, Math.min(getWidth(), dp(340)), LayoutParams.WRAP_CONTENT, true);
        variantPopup.setBackgroundDrawable(shape(keyColor));
        variantPopup.setOutsideTouchable(true);
        variantPopup.setElevation(dp(8));
        variantPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        choices.setOnItemClickListener((parent, view, position, id) -> {
            choose(variants.get(position), view);
            variantPopup.dismiss();
        });
        variantPopup.showAtLocation(this, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(40));
        return true;
    }

    private void setSearching(boolean searching) {
        this.searching = searching;
        delete.setContentDescription(searching ? "Delete search character" : "Delete from message");
        tabScroll.setVisibility(searching ? GONE : VISIBLE);
        hint.setVisibility(searching ? GONE : VISIBLE);
        searchKeys.setVisibility(searching ? VISIBLE : GONE);
        if (!searching) {
            search.clearFocus();
            requestFocus();
        }
    }

    private void buildSearchKeys() {
        searchKeys.removeAllViews();
        String[] rows = numbers
                ? new String[]{"1234567890", "@#$%&-+()", "!?/:;'\""}
                : new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = row();
            if (r == 1) row.setPadding(dp(12), 0, dp(12), 0);
            for (char c : rows[r].toCharArray()) {
                String value = String.valueOf(c);
                row.addView(button(value, value, () -> insertQuery(value)),
                        new LayoutParams(0, dp(keyHeightDp), 1));
            }
            if (r == 2) row.addView(button("⌫", "Delete search character", this::deleteQuery),
                    new LayoutParams(0, dp(keyHeightDp), 1.5f));
            searchKeys.addView(row);
        }
        LinearLayout bottom = row();
        bottom.addView(button(numbers ? "abc" : "123", "Toggle search numbers", () -> {
            numbers = !numbers;
            buildSearchKeys();
        }), new LayoutParams(0, dp(keyHeightDp), 1.4f));
        bottom.addView(button("space", "Search space", () -> insertQuery(" ")),
                new LayoutParams(0, dp(keyHeightDp), 5));
        bottom.addView(button("Done", "Show all emoji results", () -> setSearching(false)),
                new LayoutParams(0, dp(keyHeightDp), 1.8f));
        searchKeys.addView(bottom);
    }

    private void insertQuery(String value) {
        int start = Math.max(0, search.getSelectionStart());
        int end = Math.max(0, search.getSelectionEnd());
        search.getText().replace(Math.min(start, end), Math.max(start, end), value);
    }

    private void deleteQuery() {
        int start = Math.max(0, search.getSelectionStart());
        int end = Math.max(0, search.getSelectionEnd());
        int low = Math.min(start, end);
        int high = Math.max(start, end);
        if (low == high && low > 0) low = Character.offsetByCodePoints(search.getText(), low, -1);
        search.getText().delete(low, high);
    }

    private TextView emojiCell(EmojiCatalog.Entry entry, View recycled) {
        TextView cell = recycled instanceof TextView ? (TextView) recycled : label("", 28);
        cell.setText(entry.emoji);
        boolean hasVariants = catalog.variants(entry).size() > 1;
        cell.setContentDescription(entry.name + (hasVariants ? ", hold for skin tones" : ""));
        cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        return cell;
    }

    private TextView button(String text, String description, Runnable action) {
        TextView view = label(text, 14);
        view.setContentDescription(description);
        view.setBackground(new InsetDrawable(shape(keyColor), dp(2)));
        view.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        return view;
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(textColor);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDetachedFromWindow() {
        if (variantPopup != null) variantPopup.dismiss();
        super.onDetachedFromWindow();
    }

    private final class EmojiAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return emojiCell(visible.get(position), convertView);
        }
    }
}
