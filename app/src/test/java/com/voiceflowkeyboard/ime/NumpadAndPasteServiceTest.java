package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The number pad (opened by field type or by hand, left with ABC) and the Paste
 * chip (offered from the clipboard's description, typed on tap).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NumpadAndPasteServiceTest {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int NUMBER = InputType.TYPE_CLASS_NUMBER;
    private static final int PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;

    private ServiceController<TestService> controller;
    private TestService service;
    private View root;
    private int nextFieldId = 1;

    public static class TestService extends VoiceFlowKeyboardService {
        BaseInputConnection connection;
        EditorInfo editor;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
    }

    @Before public void setUp() {
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        service.connection = new BaseInputConnection(new View(service), true);
        service.editor = editor(TEXT);
        root = service.onCreateInputView();
        startField(TEXT);
    }

    @After public void tearDown() { controller.destroy(); }

    private EditorInfo editor(int inputType) {
        EditorInfo info = new EditorInfo();
        info.inputType = inputType;
        info.packageName = "com.example.app";
        info.fieldId = nextFieldId++;
        return info;
    }

    /** A different field: new field id, fresh connection. */
    private void startField(int inputType) {
        service.editor = editor(inputType);
        service.connection = new BaseInputConnection(new View(service), true);
        service.onStartInput(service.editor, false);
        service.onStartInputView(service.editor, false);
    }

    private void restartSameField() {
        service.onStartInput(service.editor, true);
        service.onStartInputView(service.editor, true);
    }

    private String text() {
        return service.connection.getEditable().toString();
    }

    private void tap(String label) {
        View key = key(label);
        assertNotNull("no key " + label, key);
        key.performClick();
    }

    private View key(String label) {
        return findText(root, label);
    }

    private static View findText(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())
                && view.isClickable()) {
            return view;
        }
        if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findText(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ number pad

    @Test public void aNumberFieldOpensTheNumberPad() {
        startField(NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        assertNull(key("q"));
        tap("1");
        tap("2");
        tap(".");
        tap("5");
        assertEquals("12.5", text());
        assertNotNull(key("Switch to letters"));
    }

    @Test public void aPhoneFieldGetsDialPadSymbols() {
        startField(InputType.TYPE_CLASS_PHONE);
        tap("+");
        tap("1");
        tap("*");
        tap("#");
        assertEquals("+1*#", text());
    }

    @Test public void aPinPadHasNoSpareKeys() {
        startField(NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        assertNull(key("."));
        assertNull(key("-"));
        assertNull(key("space"));
    }

    @Test public void abcStaysWhenTheSameFieldRestarts() {
        startField(NUMBER);
        tap("Switch to letters");
        assertNotNull(key("q"));
        restartSameField();
        assertNotNull(key("q"));
    }

    @Test public void aNewFieldDoesNotInheritTheSymbolsLayer() {
        tap("123");
        assertNotNull(key("#+="));
        startField(TEXT);
        // Letters, whatever their case: an empty text field auto-capitalises.
        assertNotNull(visibleLabels(root), key("Shift"));
        assertNull(visibleLabels(root), key("#+="));
    }

    private static String visibleLabels(View view) {
        StringBuilder out = new StringBuilder();
        collectLabels(view, out);
        return out.toString();
    }

    private static void collectLabels(View view, StringBuilder out) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) out.append('[').append(((TextView) view).getText()).append(']');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            out.append('{');
            for (int i = 0; i < group.getChildCount(); i++) collectLabels(group.getChildAt(i), out);
            out.append('}');
        }
    }

    @Test public void theSymbolsLayerOpensTheNumberPadForCodeAndCardFields() {
        tap("123");
        tap("Open number pad");
        tap("4");
        tap("2");
        tap("space");
        tap("4");
        tap("-");
        assertEquals("42 4-", text());
        // The same field restarting after each digit keeps the pad.
        restartSameField();
        assertNotNull(key("Switch to letters"));
    }

    @Test public void theFirstFieldAfterAColdStartGetsItsLayer() {
        // Android starts input before it asks for the keyboard view.
        ServiceController<TestService> cold = Robolectric.buildService(TestService.class).create();
        try {
            TestService fresh = cold.get();
            fresh.connection = new BaseInputConnection(new View(fresh), true);
            fresh.editor = editor(NUMBER);
            fresh.onStartInput(fresh.editor, false);
            View view = fresh.onCreateInputView();
            fresh.onStartInputView(fresh.editor, false);
            assertNotNull(findText(view, "Switch to letters"));
        } finally {
            cold.destroy();
        }
    }

    @Test public void aFieldThatChangesTypeWhileRestartingGetsItsNewLayer() {
        service.editor.inputType = NUMBER;
        restartSameField();
        assertNotNull(key("Switch to letters"));
    }

    @Test public void chineseNineKeyModeStillTypesPlainDigitsAndDots() {
        Prefs.setChineseInputEnabled(service, true);
        ReflectionHelpers.callInstanceMethod(service, "setInputMode",
                ClassParameter.from(InputMode.class, InputMode.CHINESE_KEYPAD));
        startField(NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        tap("2");
        tap(".");
        tap("5");
        assertEquals("2.5", text());
    }

    // ----------------------------------------------------------------- paste

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("test", value));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void copyingWhileTheKeyboardIsOpenOffersPaste() {
        copy("  exactly as copied\n");
        tap("Paste copied text");
        assertEquals("  exactly as copied\n", text());
        assertNull(key("Paste copied text"));
    }

    @Test public void typingRetiresTheOffer() {
        copy("later");
        assertNotNull(key("Paste copied text"));
        ReflectionHelpers.callInstanceMethod(service, "commitKey", ClassParameter.from(String.class, "a"));
        ReflectionHelpers.callInstanceMethod(service, "showIdleChips");
        assertNull(key("Paste copied text"));
    }

    @Test public void aClipOfUnknownAgeIsNotOfferedInAnOrdinaryField() {
        copy("old news");
        startField(TEXT);
        // The system reports no copy time for this clip, so it cannot be
        // shown to be recent.
        assertNull(key("Paste copied text"));
    }

    @Test public void aPasswordFieldAlwaysOffersCopiedText() {
        copy("s3cret-from-manager");
        startField(PASSWORD);
        tap("Paste copied text");
        assertEquals("s3cret-from-manager", text());
        // Still offered: a password manager copies, then the user navigates.
        assertNotNull(key("Paste copied text"));
    }

    @Test public void typingAPasswordKeepsThePasteOffer() {
        copy("s3cret-from-manager");
        startField(PASSWORD);
        ReflectionHelpers.callInstanceMethod(service, "commitKey", ClassParameter.from(String.class, "a"));
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(key("Paste copied text"));
    }

    @Test public void pasteNeverShowsDuringRetone() {
        ReflectionHelpers.setField(service, "retoneMode", true);
        copy("while retoning");
        assertNull(key("Paste copied text"));
    }

    @Test public void aModelSwitchIsAnnouncedOnceAsAChip() {
        Prefs.addModelSwitchNotice(service, "Sonnet 5 unavailable → Haiku 4.5");
        ReflectionHelpers.callInstanceMethod(service, "showIdleChips");
        assertNotNull(key("Sonnet 5 unavailable → Haiku 4.5"));
        // Gone after the keyboard hides, and never returns by itself.
        service.onWindowHidden();
        startField(TEXT);
        ReflectionHelpers.callInstanceMethod(service, "showIdleChips");
        assertNull(key("Sonnet 5 unavailable → Haiku 4.5"));
    }

    @Test public void nothingIsOfferedOnTheLockScreen() {
        copy("s3cret");
        KeyguardManager keyguard = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        shadowOf(keyguard).setKeyguardLocked(true);
        startField(PASSWORD);
        assertNull(key("Paste copied text"));
    }

    @Test public void historyAlwaysHasAPasteRowWhenTextIsCopied() {
        copy("from history");
        startField(TEXT);
        ReflectionHelpers.callInstanceMethod(service, "showHistoryPanel");
        tap("Paste clipboard");
        assertEquals("from history", text());
    }
}
