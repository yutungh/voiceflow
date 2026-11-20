package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

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

import java.util.Locale;

/**
 * What the keyboard may rewrite, keep or show depends on the field: passwords
 * and incognito editors are never rewritten, remembered or shown history, and
 * the lock screen never shows history.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SensitiveFieldServiceTest {
    private static final int PLAIN_TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int PASSWORD = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private static final int EMAIL = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;

    private ServiceController<TestService> controller;
    private TestService service;

    public static class TestService extends VoiceFlowKeyboardService {
        BaseInputConnection connection;
        EditorInfo editor;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
    }

    /** Reports its cursor the way a real text field does; BaseInputConnection returns null. */
    static class ReportingConnection extends BaseInputConnection {
        ReportingConnection(View view) {
            super(view, true);
        }

        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            Editable editable = getEditable();
            ExtractedText extracted = new ExtractedText();
            extracted.text = editable.toString();
            extracted.startOffset = 0;
            extracted.selectionStart = Selection.getSelectionStart(editable);
            extracted.selectionEnd = Selection.getSelectionEnd(editable);
            return extracted;
        }
    }

    @Before public void setUp() {
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        service.connection = new ReportingConnection(new View(service));
        service.editor = new EditorInfo();
        service.editor.inputType = PLAIN_TEXT;
        service.onCreateInputView();
        startField(PLAIN_TEXT, 0);
    }

    @After public void tearDown() { controller.destroy(); }

    private void startField(int inputType, int imeOptions) {
        service.editor = new EditorInfo();
        service.editor.inputType = inputType;
        service.editor.imeOptions = imeOptions;
        service.editor.packageName = "org.example.notes";
        service.onStartInput(service.editor, false);
    }

    private String text() {
        return service.connection.getEditable().toString();
    }

    private void type(String text) {
        service.connection.commitText(text, 1);
    }

    private void separator(String value) {
        ReflectionHelpers.callInstanceMethod(service, "commitSeparator", ClassParameter.from(String.class, value));
    }

    private void backspace() {
        ReflectionHelpers.callInstanceMethod(service, "deleteOne");
    }

    // ----------------------------------------------------- phrase replacement

    @Test public void aPhraseReplacementIsUndoneByTheNextBackspace() {
        type("try cloud code");
        separator(" ");
        assertEquals("try Claude Code ", text());
        backspace();
        assertEquals("try cloud code ", text());
        // The undo is spent: the next backspace is an ordinary one.
        backspace();
        assertEquals("try cloud code", text());
    }

    @Test public void anotherKeyDisarmsTheUndo() {
        type("try cloud code");
        separator(" ");
        ReflectionHelpers.callInstanceMethod(service, "commitKey", ClassParameter.from(String.class, "x"));
        backspace();
        assertEquals("try Claude Code ", text());
    }

    @Test public void movingTheCursorDisarmsTheUndo() {
        type("try cloud code");
        separator(" ");
        int end = text().length();
        service.onUpdateSelection(end, end, 3, 3, -1, -1);
        service.connection.setSelection(end, end);
        backspace();
        assertEquals("try Claude Code", text());
    }

    @Test public void anEditorThatCannotReportItsCursorGetsNoUndo() {
        service.connection = new BaseInputConnection(new View(service), true);
        type("try cloud code");
        separator(" ");
        backspace();
        assertEquals("try Claude Code", text());
    }

    @Test public void aPasswordIsNeverRewritten() {
        startField(PASSWORD, 0);
        type("open ai");
        separator(".");
        assertEquals("open ai.", text());
    }

    @Test public void anEmailAddressIsNeverRewritten() {
        startField(EMAIL, 0);
        type("chat gpt");
        separator(" ");
        assertEquals("chat gpt ", text());
    }

    // ------------------------------------------------------------- history

    @Test public void historyIsHiddenInPasswordFieldsAndShownElsewhere() {
        View history = ReflectionHelpers.getField(service, "topHistoryButton");
        assertEquals(View.VISIBLE, history.getVisibility());

        startField(PASSWORD, 0);
        assertEquals(View.GONE, history.getVisibility());
        ReflectionHelpers.callInstanceMethod(service, "showHistoryPanel");
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "historyMode"));

        startField(PLAIN_TEXT, 0);
        assertEquals(View.VISIBLE, history.getVisibility());
    }

    @Test public void historyIsHiddenOnTheLockScreen() {
        KeyguardManager keyguard = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        shadowOf(keyguard).setKeyguardLocked(true);
        startField(PLAIN_TEXT, 0);
        View history = ReflectionHelpers.getField(service, "topHistoryButton");
        assertEquals(View.GONE, history.getVisibility());
        ReflectionHelpers.callInstanceMethod(service, "showHistoryPanel");
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "historyMode"));
    }

    @Test public void dictationInAnIncognitoFieldKeepsNoHistory() {
        startField(PLAIN_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        dictate("secret plans", false);
        assertTrue(text(), text().toLowerCase(Locale.US).contains("secret plans"));
        assertTrue(Prefs.transcriptHistory(service).isEmpty());

        startField(PLAIN_TEXT, 0);
        dictate("ordinary note", true);
        assertEquals(1, Prefs.transcriptHistory(service).size());
    }

    @Test public void aResultFromAnotherAppIsKeptInHistoryNotTyped() {
        startField(PLAIN_TEXT, 0);
        service.connection.getEditable().clear();
        ReflectionHelpers.callInstanceMethod(service, "processTranscribedCapture",
                ClassParameter.from(String.class, "the Pinewood QBR moved to Friday"),
                ClassParameter.from(String.class, Prefs.PRESET_RAW),
                ClassParameter.from(int.class, Prefs.DEFAULT_EXPRESSION),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, true),
                ClassParameter.from(DeliveryGuard.Target.class,
                        new DeliveryGuard.Target("org.example.mail", "", 0)),
                ClassParameter.from(java.util.List.class, new java.util.ArrayList<PhraseReplacement>()),
                ClassParameter.from(TidyWords.Block.class, TidyWords.Block.EMPTY));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("", text());
        assertEquals(1, Prefs.transcriptHistory(service).size());
    }

    @Test public void aResultIsNotTypedIntoAFieldFocusedAfterRecordingStarted() {
        startField(PLAIN_TEXT, 0);
        DeliveryGuard.Target startedIn = ReflectionHelpers.callInstanceMethod(service, "currentDeliveryTarget");
        // He taps another field of the same app (same metadata) while it transcribes.
        startField(PLAIN_TEXT, 0);
        service.connection.getEditable().clear();
        ReflectionHelpers.callInstanceMethod(service, "processTranscribedCapture",
                ClassParameter.from(String.class, "meet Milo at Pinewood"),
                ClassParameter.from(String.class, Prefs.PRESET_RAW),
                ClassParameter.from(int.class, Prefs.DEFAULT_EXPRESSION),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, true),
                ClassParameter.from(DeliveryGuard.Target.class, startedIn),
                ClassParameter.from(java.util.List.class, new java.util.ArrayList<PhraseReplacement>()),
                ClassParameter.from(TidyWords.Block.class, TidyWords.Block.EMPTY));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("", text());
        assertEquals(1, Prefs.transcriptHistory(service).size());
    }

    @Test public void correctionsApplyOnceWithNoChain() {
        startField(PLAIN_TEXT, 0);
        service.connection.getEditable().clear();
        java.util.List<PhraseReplacement> corrections = new java.util.ArrayList<>();
        corrections.add(new PhraseReplacement("Castrel", "Kestrel"));
        corrections.add(new PhraseReplacement("Kestrel", "Falcon"));
        ReflectionHelpers.callInstanceMethod(service, "processTranscribedCapture",
                ClassParameter.from(String.class, "meet at Castrel lane"),
                ClassParameter.from(String.class, Prefs.PRESET_RAW),
                ClassParameter.from(int.class, Prefs.DEFAULT_EXPRESSION),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, true),
                ClassParameter.from(DeliveryGuard.Target.class,
                        ReflectionHelpers.<DeliveryGuard.Target>callInstanceMethod(service, "currentDeliveryTarget")),
                ClassParameter.from(java.util.List.class, corrections),
                ClassParameter.from(TidyWords.Block.class, TidyWords.Block.EMPTY));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(text(), text().contains("Kestrel"));
        assertFalse(text(), text().contains("Falcon"));
    }

    private void dictate(String transcript, boolean rememberHistory) {
        service.connection.getEditable().clear();
        ReflectionHelpers.callInstanceMethod(service, "processTranscribedCapture",
                ClassParameter.from(String.class, transcript),
                ClassParameter.from(String.class, Prefs.PRESET_RAW),
                ClassParameter.from(int.class, Prefs.DEFAULT_EXPRESSION),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, rememberHistory),
                ClassParameter.from(DeliveryGuard.Target.class,
                        ReflectionHelpers.<DeliveryGuard.Target>callInstanceMethod(service, "currentDeliveryTarget")),
                ClassParameter.from(java.util.List.class, new java.util.ArrayList<PhraseReplacement>()),
                ClassParameter.from(TidyWords.Block.class, TidyWords.Block.EMPTY));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void theRecordingDecisionFollowsTheField() {
        assertTrue(ReflectionHelpers.<Boolean>callInstanceMethod(service, "canRememberFromThisField"));
        startField(PLAIN_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        assertFalse(ReflectionHelpers.<Boolean>callInstanceMethod(service, "canRememberFromThisField"));
        startField(PASSWORD, 0);
        assertFalse(ReflectionHelpers.<Boolean>callInstanceMethod(service, "canRememberFromThisField"));
        startField(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0);
        assertFalse(ReflectionHelpers.<Boolean>callInstanceMethod(service, "canRememberFromThisField"));
    }

    // -------------------------------------------------------- learned words

    @Test public void anIncognitoFieldLearnsNoWords() {
        startField(PLAIN_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        learn("zorblax");
        assertFalse(Prefs.isLearnedWord(service, "zorblax"));

        startField(PLAIN_TEXT, 0);
        learn("zorblax");
        assertTrue(Prefs.isLearnedWord(service, "zorblax"));
    }

    private void learn(String word) {
        ReflectionHelpers.callInstanceMethod(service, "learnAutoCorrectionWord", ClassParameter.from(String.class, word));
    }

    // ------------------------------------------------------ long delete hold

    @Test public void theLongHoldClearsEverythingBeforeTheCursor() {
        type("hello there world");
        service.connection.setSelection(11, 11);
        assertTrue(ReflectionHelpers.<Boolean>callInstanceMethod(service, "deleteAllBeforeCursor"));
        assertEquals(" world", text());
        assertFalse(ReflectionHelpers.<Boolean>callInstanceMethod(service, "deleteAllBeforeCursor"));
    }
}
