package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.SeekBar;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;

/**
 * The new keyboard bar, driven through the real service: Dictate with no
 * choosing (no overlay), the style sheet's one-off and Make default, the
 * Inserted bar's Undo and Original, the overflow menu, and typing exactly as
 * before (letters, backspace, space, return, symbols).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class VoiceBarServiceTest {
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

    /** An editor that reports its text and cursor, as real apps do, so insertions can be verified. */
    static class ExtractingConnection extends BaseInputConnection {
        ExtractingConnection(View view) {
            super(view, true);
        }

        @Override
        public android.view.inputmethod.ExtractedText getExtractedText(
                android.view.inputmethod.ExtractedTextRequest request, int flags) {
            android.text.Editable editable = getEditable();
            android.view.inputmethod.ExtractedText extracted = new android.view.inputmethod.ExtractedText();
            extracted.text = editable.toString();
            extracted.startOffset = 0;
            extracted.selectionStart = android.text.Selection.getSelectionStart(editable);
            extracted.selectionEnd = android.text.Selection.getSelectionEnd(editable);
            return extracted;
        }
    }

    @Before public void setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        // Online, so Dictate takes the cloud path (the recorder is Robolectric's).
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager)
                RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkCapabilities capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        shadowOf(connectivity).setNetworkCapabilities(connectivity.getActiveNetwork(), capabilities);
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        startField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root = service.onCreateInputView();
        startField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    }

    @After public void tearDown() {
        controller.destroy();
    }

    private void startField(int inputType) {
        EditorInfo info = new EditorInfo();
        info.inputType = inputType;
        info.packageName = "com.example.chat";
        info.fieldId = nextFieldId++;
        service.editor = info;
        service.connection = new ExtractingConnection(new View(service));
        service.onStartInput(info, false);
        service.onStartInputView(info, false);
        idle();
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private String text() {
        return service.connection.getEditable().toString();
    }

    private View find(String label) {
        return find(root, label);
    }

    private static View find(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText()) && view.isClickable()) return view;
        CharSequence description = view.getContentDescription();
        if (description != null && label.contentEquals(description)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = find(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static View findStarting(View view, String prefix) {
        if (view.getVisibility() != View.VISIBLE) return null;
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findStarting(group.getChildAt(i), prefix);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void tap(String label) {
        View view = find(label);
        assertNotNull("nothing visible called " + label, view);
        view.performClick();
        idle();
    }

    private void press(View view) {
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 5, 5, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 5, 5, 0));
        idle();
    }

    private boolean recording() {
        return ReflectionHelpers.getField(service, "recording");
    }

    private StyleChoice choice() {
        return ReflectionHelpers.getField(service, "choice");
    }

    private boolean sheetOpen() {
        View sheet = ReflectionHelpers.getField(service, "voiceStyleOverlay");
        return sheet.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------- the bar

    @Test public void theIdleBarHasTheStyleChipDictateHistoryAndMore() {
        assertNotNull(findStarting(root, "Style: Clean · Natural"));
        assertNotNull(find("Dictate"));
        assertNotNull(findStarting(root, "More:"));
    }

    @Test public void dictateStartsListeningWithNoStyleOverlayAndCancelReturnsToIdle() {
        tap("Dictate");
        assertTrue(recording());
        assertFalse("no choosing: the style sheet does not open by itself", sheetOpen());
        assertNotNull(find("Stop and insert"));
        assertNotNull(find("Cancel recording"));
        assertNotNull("the style chip stays one tap away", findStarting(root, "Style: Clean"));
        tap("Cancel recording");
        assertFalse(recording());
        assertNotNull(find("Dictate"));
        assertEquals("", text());
    }

    @Test public void aStylePickedInTheSheetIsForOneMessageOnly() {
        findStarting(root, "Style: ").performClick();
        idle();
        assertTrue(sheetOpen());
        tap("Style Friends");
        assertEquals(Prefs.PRESET_CASUAL, choice().preset);
        assertEquals("the saved default is untouched", Prefs.PRESET_CLEAN, Prefs.activePreset(service));
        tap("Close styles");
        tap("Dictate");
        assertEquals("the recording keeps the one-off", Prefs.PRESET_CASUAL, choice().preset);
        tap("Cancel recording");
        assertEquals("after the message, back to the default", Prefs.PRESET_CLEAN, choice().preset);
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(service));
    }

    @Test public void theExpressionSliderNeverSavesButMakeDefaultDoes() {
        findStarting(root, "Style: ").performClick();
        idle();
        SeekBar slider = ReflectionHelpers.getField(service, "expressionSlider");
        // A drag to Lively, as the slider reports one: progress from the user, then release.
        SeekBar.OnSeekBarChangeListener listener = ReflectionHelpers.getField(slider, "mOnSeekBarChangeListener");
        slider.setProgress(Prefs.EXPRESSION_LIVELY);
        listener.onProgressChanged(slider, Prefs.EXPRESSION_LIVELY, true);
        listener.onStopTrackingTouch(slider);
        idle();
        assertEquals(Prefs.EXPRESSION_LIVELY, choice().expression);
        assertEquals("not saved", Prefs.EXPRESSION_NATURAL, Prefs.expressionForPreset(service, Prefs.PRESET_CLEAN));

        tap("Style Work");
        tap("Make Work · Natural your default");
        assertEquals(Prefs.PRESET_BUSINESS, Prefs.activePreset(service));
        assertNotNull(find("Your default is Work · Natural"));
    }

    @Test public void theKeyboardHidingEndsAOneOffChoice() {
        findStarting(root, "Style: ").performClick();
        idle();
        tap("Style Friends");
        service.onWindowHidden();
        idle();
        assertEquals(Prefs.PRESET_CLEAN, choice().preset);
        assertFalse(sheetOpen());
    }

    // ------------------------------------------------------------ after inserting

    private void insert(String raw, String inserted) {
        String historyId = Prefs.addTranscriptHistory(service, raw, inserted, Prefs.PRESET_CLEAN,
                Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        service.connection.commitText(inserted, 1);
        ReflectionHelpers.callInstanceMethod(service, "rememberLastVoiceInsertion",
                ClassParameter.from(String.class, raw),
                ClassParameter.from(String.class, inserted),
                ClassParameter.from(String.class, Prefs.PRESET_CLEAN),
                ClassParameter.from(int.class, Prefs.EXPRESSION_NATURAL),
                ClassParameter.from(String.class, VoiceHistoryItem.OPERATION_DICTATION),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(String.class, historyId));
        // As a finished dictation does.
        ReflectionHelpers.callInstanceMethod(service, "showIdleChips");
        idle();
    }

    @Test public void theInsertedBarOffersUndoOriginalAndFeedbackForEightSeconds() {
        insert("um so seven works or no wait seven thirty", "Seven thirty works.");
        assertNotNull(find("Undo: remove the inserted text"));
        assertNotNull(find("Use your original words instead"));
        assertNotNull(find("Feedback: open this dictation in History"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VoiceBar.INSERTED_MS + 100));
        assertNull(find("Undo: remove the inserted text"));
        assertNotNull(find("Dictate"));
        assertEquals("the text stays", "Seven thirty works.", text());
    }

    @Test public void undoRemovesOnlyTheVerifiedInsertion() {
        service.connection.commitText("Hi Milo. ", 1);
        insert("see you at the pinewood", "See you at Pinewood.");
        tap("Undo: remove the inserted text");
        assertEquals("Hi Milo. ", text());
    }

    @Test public void undoLeavesAFieldThatChangedAlone() {
        insert("see you at the pinewood", "See you at Pinewood.");
        service.connection.commitText(" Thanks", 1);
        // The cursor moved: the insertion is no longer the last thing, so nothing is removed.
        ReflectionHelpers.callInstanceMethod(service, "undoLastVoiceInsertion");
        assertEquals("See you at Pinewood. Thanks", text());
    }

    @Test public void undoNeverActsOnASelectionOrAnUnknownCursor() {
        service.connection.commitText("A hi gap", 1);
        insert("hi", " hi");
        // He selects " gap hi" before tapping Undo: nothing may be removed.
        service.connection.setSelection(4, 11);
        ReflectionHelpers.callInstanceMethod(service, "undoLastVoiceInsertion");
        assertEquals("A hi gap hi", text());
    }

    @Test public void clearingSuggestionsBringsBackTheIdleBar() {
        ReflectionHelpers.callInstanceMethod(service, "setPendingAutoComplete",
                ClassParameter.from(String.class, "Satu"),
                ClassParameter.from(java.util.List.class, java.util.Arrays.asList("Saturday", "Saturn")));
        ReflectionHelpers.callInstanceMethod(service, "showIdleChips");
        idle();
        assertNotNull(find("Saturday"));
        assertNull("suggestions replace the style chip", findStarting(root, "Style: "));
        ReflectionHelpers.callInstanceMethod(service, "clearAutoCorrection");
        idle();
        assertNull(find("Saturday"));
        assertNotNull("no empty strip left behind", findStarting(root, "Style: "));
    }

    @Test public void cancellingARetoneKeepsTheInsertedBarsEightSeconds() {
        Prefs.saveApiKeys(service, "sk-test-not-real", "", "", "");
        insert("um see you at the pinewood after the qbr on thursday", "See you at Pinewood after the QBR on Thursday.");
        ReflectionHelpers.callInstanceMethod(service, "openRetoneOverlay");
        idle();
        assertTrue(sheetOpen());
        tap("Cancel retone");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(VoiceBar.INSERTED_MS + 100));
        assertNull(find("Undo: remove the inserted text"));
        assertNotNull(find("Dictate"));
    }

    @Test public void destroyingAKeyboardThatWasNeverShownIsSafe() {
        ServiceController<TestService> unseen = Robolectric.buildService(TestService.class).create();
        unseen.destroy();
    }

    @Test public void originalSwapsInHisWordsAndUseCleanedSwapsBack() {
        insert("um see you at the pinewood after the qbr on thursday", "See you at Pinewood after the QBR on Thursday.");
        tap("Use your original words instead");
        assertEquals("um see you at the pinewood after the qbr on thursday", text());
        tap("Use the tidied text again");
        assertEquals("See you at Pinewood after the QBR on Thursday.", text());
    }

    @Test public void typingAfterAnInsertionGoesBackToTheIdleBar() {
        insert("hello febe", "Hello Febe.");
        tap("Q");
        assertNull(find("Undo: remove the inserted text"));
        assertTrue(text(), text().endsWith("Q"));
    }

    // -------------------------------------------------------------- the overflow

    @Test public void translateCreateVoiceEditSpellingAndSettingsAreInMore() {
        findStarting(root, "More:").performClick();
        idle();
        ShadowPopupMenu menu = shadowOf(ShadowPopupMenu.getLatestPopupMenu());
        assertNotNull(menu);
        StringBuilder items = new StringBuilder();
        android.view.Menu m = ShadowPopupMenu.getLatestPopupMenu().getMenu();
        for (int i = 0; i < m.size(); i++) {
            items.append(m.getItem(i).getTitle()).append('|');
        }
        String all = items.toString();
        assertTrue(all, all.contains("Create text by voice"));
        assertTrue(all, all.contains("Edit this text by voice"));
        assertTrue(all, all.contains("Fix spelling"));
        assertTrue(all, all.contains("VoiceFlow settings"));
    }

    // -------------------------------------------------- typing works exactly as before

    @Test public void lettersBackspaceSpaceAndReturnType() {
        // An empty field starts capitalised.
        tap("H");
        tap("i");
        press(find("Space"));
        tap("y");
        press(find("Delete"));
        assertEquals("Hi ", text());
        tap("Return");
        assertEquals("a multi-line field gets a new line", "Hi \n", text());
    }

    @Test public void shiftStillWorks() {
        tap("H");
        tap("i");
        tap("Shift");
        tap("A");
        tap("b");
        assertEquals("shift lasts one letter", "HiAb", text());
    }

    @Test public void symbolsAndBackToLetters() {
        tap("123");
        tap("1");
        tap("ABC");
        tap("b");
        assertEquals("1b", text());
    }

    @Test public void theReturnKeyFollowsTheFieldsAction() {
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT;
        info.imeOptions = EditorInfo.IME_ACTION_SEND;
        info.packageName = "com.example.chat";
        info.fieldId = 99;
        service.editor = info;
        service.onStartInput(info, false);
        service.onStartInputView(info, false);
        idle();
        assertNotNull(find("Send"));
    }
}
