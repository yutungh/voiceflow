package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.8.6 through the real service: double-tap Shift for caps lock, the recording
 * panel in the keys' place (style chips, More, the expression slider), the keys
 * coming back on every way out, and desktop's Nothing heard and Copied fallbacks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RecordingPanelServiceTest {
    private ServiceController<TestService> controller;
    private TestService service;
    private View root;
    private EditorInfo editor;
    private int nextFieldId = 1;

    public static class TestService extends VoiceFlowKeyboardService {
        BaseInputConnection connection;
        EditorInfo editor;
        @Override public InputConnection getCurrentInputConnection() { return connection; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return editor; }
    }

    @Before public void setUp() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager)
                RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkCapabilities capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        shadowOf(connectivity).setNetworkCapabilities(connectivity.getActiveNetwork(), capabilities);
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        startField();
        root = service.onCreateInputView();
        startField();
    }

    @After public void tearDown() {
        controller.destroy();
    }

    private void startField() {
        editor = new EditorInfo();
        editor.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        editor.packageName = "com.example.chat";
        editor.fieldId = nextFieldId++;
        service.editor = editor;
        service.connection = new VoiceBarServiceTest.ExtractingConnection(new View(service));
        service.onStartInput(editor, false);
        service.onStartInputView(editor, false);
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

    private void touchDown(View view, long at) {
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, 5, 5, 0);
        MotionEvent up = MotionEvent.obtain(at, at + 40, MotionEvent.ACTION_UP, 5, 5, 0);
        view.dispatchTouchEvent(down);
        view.dispatchTouchEvent(up);
        idle();
    }

    private boolean field(String name) {
        return ReflectionHelpers.getField(service, name);
    }

    private StyleChoice choice() {
        return ReflectionHelpers.getField(service, "choice");
    }

    private View panel() {
        return ReflectionHelpers.getField(service, "recordingPanel");
    }

    private View keys() {
        return ReflectionHelpers.getField(service, "keyboardPanel");
    }

    private boolean sheetOpen() {
        View sheet = ReflectionHelpers.getField(service, "voiceStyleOverlay");
        return sheet.getVisibility() == View.VISIBLE;
    }

    private String barMessage() {
        return ReflectionHelpers.getField(service, "barMessage");
    }

    private void assertKeysBack() {
        assertEquals("the panel is gone", View.GONE, panel().getVisibility());
        assertEquals("the keys are back", View.VISIBLE, keys().getVisibility());
        assertFalse(field("capturePanel"));
        assertNotNull(find("Shift"));
    }

    // ------------------------------------------------------------------ shift

    @Test public void doubleTapShiftLocksCapsEvenAtTheStartOfASentence() {
        // The empty field starts with an automatic capital.
        assertTrue(field("autoShift"));
        tap("Shift");
        tap("Shift");
        assertTrue("two taps lock caps", field("capsLock"));
        tap("A");
        tap("B");
        tap("C");
        assertEquals("caps lock lasts", "ABC", text());
        tap("Shift");
        assertFalse("one tap releases it", field("capsLock"));
        tap("d");
        assertEquals("ABCd", text());
    }

    @Test public void theShiftKeyActsOnTouchDownAndTimesTheDoubleTapBetweenPresses() {
        View shift = find("Shift");
        long t = SystemClock.uptimeMillis();
        touchDown(shift, t);
        touchDown(shift, t + 250);
        assertTrue(field("capsLock"));
        touchDown(shift, t + 300);
        assertFalse(field("capsLock"));
        // Slow taps are two single taps.
        touchDown(shift, t + 2000);
        touchDown(shift, t + 2000 + ShiftRules.DOUBLE_TAP_MS + 100);
        assertFalse(field("capsLock"));
    }

    @Test public void capsLockIsShownOnTheKeyAndToTalkBack() {
        tap("Shift");
        tap("Shift");
        View shift = find("Shift");
        assertNotNull(shift);
        assertEquals("Caps lock", shift.getStateDescription().toString());
        tap("Shift");
        shift = find("Shift");
        assertEquals("Off", shift.getStateDescription().toString());
    }

    @Test public void aFieldThatRestartsItselfKeepsCapsLock() {
        tap("Shift");
        tap("Shift");
        tap("A");
        // As the editor reports after the keystroke.
        service.onUpdateSelection(0, 0, 1, 1, -1, -1);
        // Some apps restart the same field after every change.
        service.onStartInput(editor, true);
        service.onStartInputView(editor, true);
        idle();
        assertTrue(field("capsLock"));
        tap("B");
        assertEquals("AB", text());
    }

    @Test public void aRestartIntoAnotherLookAlikeFieldClearsCapsLock() {
        tap("Shift");
        tap("Shift");
        tap("A");
        service.onUpdateSelection(0, 0, 1, 1, -1, -1);
        // A WebView moving to its next field: same package, id and type, a restart,
        // but not the text he just typed (Codex verification).
        service.connection = new VoiceBarServiceTest.ExtractingConnection(new View(service));
        service.onStartInput(editor, true);
        service.onStartInputView(editor, true);
        idle();
        assertFalse(field("capsLock"));
    }

    @Test public void aNewFieldStartsWithoutCapsLock() {
        tap("Shift");
        tap("Shift");
        startField();
        assertFalse(field("capsLock"));
    }

    @Test public void aFreshStartOfALookAlikeFieldClearsCapsLock() {
        tap("Shift");
        tap("Shift");
        // Another field with the same package, id and type, started fresh (Codex review).
        service.onStartInput(editor, false);
        service.onStartInputView(editor, false);
        idle();
        assertFalse(field("capsLock"));
    }

    @Test public void returnBetweenShiftTapsMakesThemSingleTaps() {
        service.connection.commitText("Hi ", 1);
        tap("Shift");
        tap("Return");
        tap("Shift");
        assertFalse(field("capsLock"));
    }

    // ------------------------------------------------------------ recording panel

    @Test public void dictateReplacesTheKeysWithThePanelAtTheSameHeight() {
        int before = ((View) ReflectionHelpers.getField(service, "keyboardSurface")).getLayoutParams().height;
        tap("Dictate");
        assertTrue(field("recording"));
        assertEquals(View.VISIBLE, panel().getVisibility());
        assertEquals("the keys hold their place, unseen", View.INVISIBLE, keys().getVisibility());
        assertEquals("the keyboard never jumps", before,
                ((View) ReflectionHelpers.getField(service, "keyboardSurface")).getLayoutParams().height);
        assertNotNull("the style in use is a chosen chip", find("Style Clean, chosen"));
        assertNotNull(findStarting(panel(), "More styles"));
        assertNotNull("the slider is there", ReflectionHelpers.getField(service, "panelSlider"));
        assertNotNull(find("Stop and insert"));
        assertNotNull(find("Cancel recording"));
        assertFalse("nothing opens by itself", sheetOpen());
        tap("Cancel recording");
        assertKeysBack();
        assertEquals("", text());
    }

    @Test public void aChipIsOneTapAndForThisMessageOnly() {
        tap("Dictate");
        tap("Style Friends");
        assertEquals(Prefs.PRESET_CASUAL, choice().preset);
        assertNotNull(find("Style Friends, chosen"));
        assertFalse(sheetOpen());
        assertEquals("the default is untouched", Prefs.PRESET_CLEAN, Prefs.activePreset(service));
        tap("Cancel recording");
        assertEquals("after the message, back to the default", Prefs.PRESET_CLEAN, choice().preset);
        assertKeysBack();
    }

    @Test public void moreOpensEveryStyleAndAPickComesBackToThePanelKeepingItsPlace() {
        tap("Dictate");
        findStarting(panel(), "More styles").performClick();
        idle();
        assertTrue(sheetOpen());
        assertTrue("More opens on every style", (boolean) ReflectionHelpers.getField(service, "allStylesOpen"));
        String pirate = Prefs.promptProfile(service, Prefs.PRESET_FUN_PIRATE).name;
        tap("Style " + pirate);
        assertFalse("back to the panel", sheetOpen());
        assertEquals(Prefs.PRESET_FUN_PIRATE, choice().preset);
        assertNotNull(find("Style " + pirate + ", chosen"));
        // Another chip: the style from More keeps its chip.
        tap("Style Work");
        assertNotNull(find("Style " + pirate));
        assertEquals(Prefs.PRESET_BUSINESS, choice().preset);
        tap("Cancel recording");
        assertKeysBack();
    }

    @Test public void thePanelSliderSetsThisMessagesExpressionWithoutSaving() {
        tap("Dictate");
        SeekBar slider = ReflectionHelpers.getField(service, "panelSlider");
        assertTrue(slider.isEnabled());
        assertEquals(Prefs.EXPRESSION_EXPRESSIVE, slider.getMax());
        SeekBar.OnSeekBarChangeListener listener = ReflectionHelpers.getField(slider, "mOnSeekBarChangeListener");
        slider.setProgress(Prefs.EXPRESSION_LIVELY);
        listener.onProgressChanged(slider, Prefs.EXPRESSION_LIVELY, true);
        listener.onStopTrackingTouch(slider);
        idle();
        assertEquals(Prefs.EXPRESSION_LIVELY, choice().expression);
        assertEquals(Prefs.EXPRESSION_NATURAL, Prefs.expressionForPreset(service, Prefs.PRESET_CLEAN));
        assertFalse("a drag never opens the sheet", sheetOpen());
        assertEquals("the panel stays", View.VISIBLE, panel().getVisibility());
    }

    @Test public void anExpressionChosenInTheSheetShowsOnThePanelAfterClosing() {
        tap("Dictate");
        findStarting(panel(), "More styles").performClick();
        idle();
        SeekBar sheetSlider = ReflectionHelpers.getField(service, "expressionSlider");
        SeekBar.OnSeekBarChangeListener listener = ReflectionHelpers.getField(sheetSlider, "mOnSeekBarChangeListener");
        sheetSlider.setProgress(Prefs.EXPRESSION_RESERVED);
        listener.onProgressChanged(sheetSlider, Prefs.EXPRESSION_RESERVED, true);
        listener.onStopTrackingTouch(sheetSlider);
        idle();
        tap("Close styles");
        SeekBar panelSlider = ReflectionHelpers.getField(service, "panelSlider");
        assertEquals(Prefs.EXPRESSION_RESERVED, panelSlider.getProgress());
        assertEquals(Prefs.EXPRESSION_RESERVED, choice().expression);
        tap("Cancel recording");
    }

    @Test public void theSliderIsOffForRaw() {
        tap("Dictate");
        choice().pick(Prefs.PRESET_RAW, Prefs.EXPRESSION_NATURAL);
        ReflectionHelpers.callInstanceMethod(service, "renderRecordingPanel");
        idle();
        SeekBar slider = ReflectionHelpers.getField(service, "panelSlider");
        assertFalse(slider.isEnabled());
        tap("Cancel recording");
    }

    @Test public void afterStopThePanelStaysFixedUntilTheTextIsIn() {
        tap("Dictate");
        // As Stop does once there is audio: processing, the choice now fixed.
        ReflectionHelpers.setField(service, "recording", false);
        ReflectionHelpers.setField(service, "processing", true);
        ReflectionHelpers.callInstanceMethod(service, "showCaptureProcessingState");
        idle();
        assertEquals(View.VISIBLE, panel().getVisibility());
        View chip = find("Style Clean, chosen");
        assertNotNull(chip);
        assertFalse("fixed after Stop", chip.isEnabled());
        SeekBar slider = ReflectionHelpers.getField(service, "panelSlider");
        assertFalse(slider.isEnabled());
        ReflectionHelpers.callInstanceMethod(service, "finishProcessingState",
                ClassParameter.from(String.class, "Inserted"));
        idle();
        assertKeysBack();
    }

    @Test public void stopWithNoAudioBringsTheKeysBack() {
        tap("Dictate");
        tap("Stop and insert");
        assertFalse(field("recording"));
        assertFalse(field("processing"));
        assertKeysBack();
    }

    @Test public void aVoiceEditShowsWhatToSayAndNoStyles() {
        Prefs.saveApiKeys(service, "sk-test-not-real", "", "", "");
        service.connection.commitText("Sea you their", 1);
        ReflectionHelpers.callInstanceMethod(service, "toggleInstructionCapture");
        idle();
        assertTrue(field("recording"));
        TextView hint = ReflectionHelpers.getField(service, "recordingHintText");
        assertEquals("Say how to change the text, then tap Stop.", hint.getText().toString());
        assertNull(findStarting(panel(), "Style "));
        tap("Cancel recording");
        assertKeysBack();
    }

    // --------------------------------------------------- what happens after Stop

    private void process(String transcript, DeliveryGuard.Target target, boolean remember) {
        ReflectionHelpers.setField(service, "processing", true);
        ReflectionHelpers.setField(service, "capturePanel", true);
        ReflectionHelpers.callInstanceMethod(service, "processTranscribedCapture",
                ClassParameter.from(String.class, transcript),
                ClassParameter.from(String.class, Prefs.PRESET_RAW),
                ClassParameter.from(int.class, Prefs.EXPRESSION_NATURAL),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(String.class, ""),
                ClassParameter.from(boolean.class, remember),
                ClassParameter.from(DeliveryGuard.Target.class, target),
                ClassParameter.from(List.class, new ArrayList<PhraseReplacement>()),
                ClassParameter.from(TidyWords.Block.class, TidyWords.Block.EMPTY));
        idle();
    }

    private DeliveryGuard.Target here() {
        return ReflectionHelpers.callInstanceMethod(service, "currentDeliveryTarget");
    }

    private String clipboardText() {
        ClipboardManager clipboard = (ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
        return clipboard.hasPrimaryClip() ? clipboard.getPrimaryClip().getItemAt(0).getText().toString() : "";
    }

    @Test public void nothingHeardSaysSoAndInsertsNothing() {
        int before = Prefs.transcriptHistory(service).size();
        process("   ", here(), true);
        assertEquals("", text());
        assertEquals("Nothing heard - nothing inserted", barMessage());
        assertEquals("nothing kept", before, Prefs.transcriptHistory(service).size());
        assertKeysBack();
    }

    @Test public void aDictationGoesIntoTheFieldItStartedIn() {
        process("see you at pinewood", here(), true);
        assertTrue(text(), text().contains("see you at pinewood"));
        assertKeysBack();
    }

    @Test public void movedToAnotherFieldTheTextIsCopiedInstead() {
        process("see you at pinewood", new DeliveryGuard.Target("com.example.other", "x", 999), true);
        assertEquals("nothing goes into this field", "", text());
        assertEquals("see you at pinewood", clipboardText());
        assertTrue(barMessage(), barMessage().startsWith("Copied"));
        assertKeysBack();
    }

    @Test public void aPrivateDictationIsNeverCopied() {
        process("my secret words", new DeliveryGuard.Target("com.example.other", "x", 999), false);
        assertEquals("", clipboardText());
        assertTrue(barMessage(), barMessage().startsWith("Not inserted"));
    }

    // -------------------------------------------------------- History Use cleaned

    @Test public void theHeldBackTidyUpIsKeptOnlyForEntriesInHistory() {
        String id = Prefs.addTranscriptHistory(service, "raw words", "his words", Prefs.PRESET_CLEAN,
                Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        Prefs.setHeldBackCleanup(service, id, "Tidied words.", Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL);
        Prefs.setHeldBackCleanup(service, "not-in-history", "Lost words.", Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL);
        assertEquals("Tidied words.", Prefs.heldBackCleanup(service, id));
        assertEquals("", Prefs.heldBackCleanup(service, "not-in-history"));
        Prefs.clearHeldBackCleanup(service, id);
        assertEquals("", Prefs.heldBackCleanup(service, id));
    }

    @Test public void anEntryLeavingHistoryTakesItsHeldBackTidyUpWithIt() {
        String id = Prefs.addTranscriptHistory(service, "raw words", "his words", Prefs.PRESET_CLEAN,
                Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        Prefs.setHeldBackCleanup(service, id, "Tidied words.", Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL);
        for (int i = 0; i < 25; i++) {
            Prefs.addTranscriptHistory(service, "later " + i, "Later " + i + ".", Prefs.PRESET_CLEAN,
                    Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        }
        // The lookup reads the store by id, so an empty answer means the entry was pruned.
        assertEquals("", Prefs.heldBackCleanup(service, id));
    }

    @Test public void useCleanedSavesUnderItsOwnStyleWhateverVersionIsSelected() {
        Context app = RuntimeEnvironment.getApplication();
        String id = Prefs.addTranscriptHistory(app, "raw mixed words", "his mixed words",
                Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        Prefs.setHeldBackCleanup(app, id, "Tidied mixed words.", Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL);
        // He switched this entry to Original from the keyboard's History first.
        Prefs.selectTranscriptHistoryVariant(app, id, Prefs.PRESET_RAW, Prefs.EXPRESSION_NATURAL);
        assertEquals("Tidied mixed words.", Prefs.heldBackCleanup(app, id));
        Intent intent = new Intent(app, TranscriptDetailActivity.class)
                .putExtra(TranscriptDetailActivity.EXTRA_HISTORY_ID, id);
        ActivityController<TranscriptDetailActivity> activity =
                Robolectric.buildActivity(TranscriptDetailActivity.class, intent).create().start().resume().visible();
        find(activity.get().getWindow().getDecorView(), "Use cleaned: copy the tidied text and keep it in History").performClick();
        idle();
        VoiceHistoryItem item = null;
        for (VoiceHistoryItem candidate : Prefs.transcriptHistory(app)) {
            if (candidate.id.equals(id)) item = candidate;
        }
        assertNotNull(item);
        assertEquals("kept under Clean, never lost", "Tidied mixed words.",
                item.outputForVariant(Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL));
        activity.pause().stop().destroy();
    }

    @Test public void historyUseCleanedMakesTheTidyUpTheEntrysTextAndCopiesIt() {
        Context app = RuntimeEnvironment.getApplication();
        String id = Prefs.addTranscriptHistory(app, "I talked to mom and 她说不来了", "I talked to mom and 她说不来了",
                Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL, VoiceHistoryItem.OPERATION_DICTATION, "");
        Prefs.setHeldBackCleanup(app, id, "I talked to Mom, and she said she isn't coming.",
                Prefs.PRESET_CLEAN, Prefs.EXPRESSION_NATURAL);
        Intent intent = new Intent(app, TranscriptDetailActivity.class)
                .putExtra(TranscriptDetailActivity.EXTRA_HISTORY_ID, id);
        ActivityController<TranscriptDetailActivity> activity =
                Robolectric.buildActivity(TranscriptDetailActivity.class, intent).create().start().resume().visible();
        View page = activity.get().getWindow().getDecorView();
        View use = find(page, "Use cleaned: copy the tidied text and keep it in History");
        assertNotNull(use);
        use.performClick();
        idle();
        VoiceHistoryItem item = null;
        for (VoiceHistoryItem candidate : Prefs.transcriptHistory(app)) {
            if (candidate.id.equals(id)) item = candidate;
        }
        assertNotNull(item);
        assertEquals("I talked to Mom, and she said she isn't coming.", item.finalText);
        assertEquals("", Prefs.heldBackCleanup(app, id));
        ClipboardManager clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("I talked to Mom, and she said she isn't coming.",
                clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertNull("offered once", find(activity.get().getWindow().getDecorView(),
                "Use cleaned: copy the tidied text and keep it in History"));
        activity.pause().stop().destroy();
    }
}
