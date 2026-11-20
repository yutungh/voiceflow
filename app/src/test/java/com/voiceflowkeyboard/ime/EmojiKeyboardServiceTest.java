package com.voiceflowkeyboard.ime;

import static org.junit.Assert.*;
import static com.voiceflowkeyboard.ime.EmojiPickerViewTest.find;
import static com.voiceflowkeyboard.ime.EmojiPickerViewTest.grid;

import android.text.InputType;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.GridView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.util.ReflectionHelpers;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EmojiKeyboardServiceTest {
    private ServiceController<TestService> controller;
    private TestService service;
    private View root;

    public static class TestService extends VoiceFlowKeyboardService {
        BaseInputConnection connection;
        EditorInfo editor;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
    }

    @Before public void setUp() throws Exception {
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        service.editor = new EditorInfo();
        service.editor.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        service.connection = new BaseInputConnection(new View(service), true);
        try (InputStreamReader reader = new InputStreamReader(
                service.getAssets().open("emoji/catalog.tsv"), StandardCharsets.UTF_8)) {
            ReflectionHelpers.setField(service, "emojiCatalog", EmojiCatalog.read(reader));
        }
        root = service.onCreateInputView();
        service.onStartInput(service.editor, false);
    }

    @After public void tearDown() { controller.destroy(); }

    @Test public void searchNeverWritesToEditorAndEmojiCommitSurvivesEditorRestart() {
        service.connection.commitText("message ", 1);
        find(root, "Open emoji keyboard").performClick();
        EditText search = (EditText) find(root, "Search emojis");
        search.performClick();
        for (String key : new String[]{"d", "o", "g"}) find(root, key).performClick();
        assertEquals("message ", service.connection.getEditable().toString());
        service.onStartInput(service.editor, true);
        service.onStartInputView(service.editor, true);
        assertSame(search, find(root, "Search emojis"));
        GridView grid = grid(root);
        grid.performItemClick(grid.getAdapter().getView(0, null, grid), 0, 0);
        assertEquals("message 🐕", service.connection.getEditable().toString());
        find(root, "Return to keyboard").performClick();
        assertNull(find(root, "Search emojis"));
        assertNotNull(find(root, "Open emoji keyboard"));
    }

    @Test public void changingEditorsClosesPickerAndRestoresRegularHeight() {
        find(root, "Open emoji keyboard").performClick();
        assertNotNull(find(root, "Search emojis"));
        service.onFinishInput();
        service.connection = new BaseInputConnection(new View(service), true);
        service.onStartInput(service.editor, false);
        assertNull(find(root, "Search emojis"));
        assertNotNull(find(root, "Open emoji keyboard"));
        android.widget.FrameLayout surface = ReflectionHelpers.getField(service, "keyboardSurface");
        int expected = Math.round(48 * 4 * service.getResources().getDisplayMetrics().density);
        assertEquals(expected, surface.getLayoutParams().height);
        assertEquals("", service.connection.getEditable().toString());
    }

    @Test public void pickerBackspaceDeletesWholeSequencesAndRespectsSelection() {
        find(root, "Open emoji keyboard").performClick();
        for (String emoji : new String[]{"👨‍👩‍👧‍👦", "👍🏽", "🇺🇸", "1️⃣"}) {
            service.connection.getEditable().clear();
            service.connection.commitText("hello " + emoji, 1);
            find(root, "Delete from message").performClick();
            assertEquals("hello ", service.connection.getEditable().toString());
        }
        service.connection.getEditable().clear();
        service.connection.commitText("one 😀 two", 1);
        service.connection.setSelection(4, 6);
        find(root, "Delete from message").performClick();
        assertEquals("one  two", service.connection.getEditable().toString());
    }
}
