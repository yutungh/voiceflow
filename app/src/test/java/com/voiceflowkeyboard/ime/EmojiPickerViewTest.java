package com.voiceflowkeyboard.ime;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.PopupWindow;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EmojiPickerViewTest {
    private Activity activity;
    private EmojiCatalog catalog;
    private String committed = "";
    private boolean closed;
    private int deletes;
    private boolean acceptInsertion = true;

    @Before public void setUp() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("emoji_picker", Context.MODE_PRIVATE).edit().clear().commit();
        try (InputStreamReader reader = new InputStreamReader(
                activity.getAssets().open("emoji/catalog.tsv"), StandardCharsets.UTF_8)) {
            catalog = EmojiCatalog.read(reader);
        }
    }

    @Test public void searchKeysEditOnlyQueryAndAnEmojiCommitsOnlyOnTap() {
        EmojiPickerView picker = picker(true, 48);
        EditText search = (EditText) find(picker, "Search emojis");
        search.performClick();
        for (String key : new String[]{"d", "o", "g"}) find(picker, key).performClick();
        assertEquals("dog", search.getText().toString());
        assertEquals("", committed);
        GridView grid = grid(picker);
        assertTrue(grid.getAdapter().getCount() > 0);
        tapFirst(grid);
        assertEquals("🐕", committed);
        assertEquals("dog", search.getText().toString());
        find(picker, "Delete search character").performClick();
        assertEquals("do", search.getText().toString());
        assertEquals(0, deletes);
        picker.handleBack();
        assertFalse(picker.isSearching());
        assertFalse(closed);
        picker.handleBack();
        assertTrue(closed);
    }

    @Test public void searchSupportsSelectionsClearEmptyStateAndCategories() {
        EmojiPickerView picker = picker(true, 48);
        EditText search = (EditText) find(picker, "Search emojis");
        search.performClick();
        search.setText("doog");
        search.setSelection(1, 3);
        find(picker, "o").performClick();
        assertEquals("dog", search.getText().toString());
        search.setText("there-is-no-emoji-zzzz");
        assertEquals(0, grid(picker).getAdapter().getCount());
        find(picker, "Clear emoji search").performClick();
        find(picker, "Show all emoji results").performClick();
        find(picker, "Food & Drink").performClick();
        GridView grid = grid(picker);
        for (int i = 0; i < grid.getAdapter().getCount(); i++) {
            assertEquals("Food & Drink", ((EmojiCatalog.Entry) grid.getAdapter().getItem(i)).category);
        }
    }

    @Test public void recentsPersistOnlyWhenAllowedAndAfterSuccessfulInsertion() {
        EmojiPickerView picker = picker(true, 48);
        tapFirst(grid(picker));
        assertFalse(committed.isEmpty());
        EmojiPickerView reopened = picker(false, 48);
        assertEquals(committed, ((EmojiCatalog.Entry) grid(reopened).getAdapter().getItem(0)).emoji);
        EditText query = (EditText) find(reopened, "Search emojis");
        query.setText("dog");
        committed = "";
        tapFirst(grid(reopened));
        assertEquals("🐕", committed);
        String saved = activity.getSharedPreferences("emoji_picker", Context.MODE_PRIVATE).getString("recent", "");
        assertFalse(saved.contains("🐕"));
        EmojiPickerView rejected = picker(true, 48);
        ((EditText) find(rejected, "Search emojis")).setText("cat");
        acceptInsertion = false;
        tapFirst(grid(rejected));
        assertEquals(saved, activity.getSharedPreferences("emoji_picker", Context.MODE_PRIVATE).getString("recent", ""));
    }

    @Test public void browseAndSearchHaveVisibleResultsOnNarrowAndWideLayouts() {
        for (int[] size : new int[][]{{320, 292, 48}, {384, 292, 48}, {752, 308, 52}, {730, 250, 32}}) {
            EmojiPickerView picker = picker(true, size[2]);
            layout(picker, size[0], size[1]);
            assertTrue(grid(picker).getHeight() >= dp(96));
            find(picker, "Search emojis").performClick();
            layout(picker, size[0], size[1]);
            assertTrue("Results must retain one touchable row", grid(picker).getHeight() >= dp(48));
            assertTrue(find(picker, "Search emojis").getWidth() >= dp(120));
            View done = find(picker, "Show all emoji results");
            assertEquals(dp(size[2]), done.getHeight());
        }
    }

    @Test public void longPressOffersSkinTonesAndDismissesAfterSelection() {
        EmojiPickerView picker = picker(true, 48);
        layout(picker, 384, 292);
        ((EditText) find(picker, "Search emojis")).setText("thumbs up");
        GridView grid = grid(picker);
        View thumb = grid.getAdapter().getView(0, null, grid);
        assertTrue(grid.getOnItemLongClickListener().onItemLongClick(grid, thumb, 0, 0));
        PopupWindow popup = ReflectionHelpers.getField(picker, "variantPopup");
        assertTrue(popup.isShowing());
        GridView tones = grid(popup.getContentView());
        assertEquals(6, tones.getAdapter().getCount());
        for (int i = 0; i < tones.getAdapter().getCount(); i++) {
            EmojiCatalog.Entry entry = (EmojiCatalog.Entry) tones.getAdapter().getItem(i);
            if ("👍🏽".equals(entry.emoji)) {
                tones.performItemClick(tones.getAdapter().getView(i, null, tones), i, i);
                break;
            }
        }
        assertEquals("👍🏽", committed);
        assertFalse(popup.isShowing());
    }

    private EmojiPickerView picker(boolean remember, int keyHeight) {
        EmojiPickerView picker = new EmojiPickerView(activity, Color.LTGRAY, Color.BLACK,
                Color.WHITE, Color.BLUE, keyHeight, remember, new EmojiPickerView.Listener() {
            @Override public boolean onEmoji(String emoji) {
                if (!acceptInsertion) return false;
                committed += emoji;
                return true;
            }
            @Override public void onClose() { closed = true; }
            @Override public void onDelete() { deletes++; }
        });
        picker.setCatalog(catalog);
        activity.setContentView(picker);
        return picker;
    }

    private void tapFirst(GridView grid) {
        grid.performItemClick(grid.getAdapter().getView(0, null, grid), 0, 0);
    }

    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY));
        view.layout(0, 0, dp(width), dp(height));
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    static View find(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), description);
                if (match != null) return match;
            }
        }
        return null;
    }

    static GridView grid(View root) {
        if (root instanceof GridView) return (GridView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                GridView match = grid(group.getChildAt(i));
                if (match != null) return match;
            }
        }
        return null;
    }
}
