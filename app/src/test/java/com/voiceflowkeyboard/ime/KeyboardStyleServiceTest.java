package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.os.Looper;
import android.text.InputType;
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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 0.9.4, through the real service: the style follows the letter keyboard (the Chinese
 * keyboard's own style, its speech model, a missing word-list style, the bar's order), Style
 * choice remembering a pick per keyboard or keeping it for one message with Make default, and
 * a style whose word list is off. Every name and word is invented.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KeyboardStyleServiceTest {
    private static final String MIXED = "list_mixed-example";

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

    @Before public void setUp() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager)
                RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkCapabilities capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        shadowOf(connectivity).setNetworkCapabilities(connectivity.getActiveNetwork(), capabilities);
        ListStyles.resetForTest();
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        Prefs.setChineseInputEnabled(service, true);
        startField();
        root = service.onCreateInputView();
        startField();
    }

    @After public void tearDown() {
        controller.destroy();
        ListStyles.resetForTest();
    }

    private void startField() throws Exception {
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        info.packageName = "com.example.chat";
        info.fieldId = nextFieldId++;
        service.editor = info;
        service.connection = new BaseInputConnection(new View(service), true);
        service.onStartInput(info, false);
        service.onStartInputView(info, false);
        drainMaintenance();
        idle();
    }

    /** The word lists are read ahead on the maintenance thread; let that finish first. */
    private void drainMaintenance() throws Exception {
        ExecutorService maintenance = ReflectionHelpers.getField(service, "maintenanceExecutor");
        maintenance.submit(() -> { }).get(30, TimeUnit.SECONDS);
    }

    private void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void publishMixed() throws Exception {
        ListStyles.publish(service, KeyboardStyleSettingsTest.listWithStyle("mixed-example", "Mixed example"));
        idle();
    }

    private void keyboard(InputMode mode) {
        ReflectionHelpers.callInstanceMethod(service, "setInputMode", ClassParameter.from(InputMode.class, mode));
        idle();
    }

    private StyleChoice choice() {
        return ReflectionHelpers.getField(service, "choice");
    }

    private boolean recording() {
        return ReflectionHelpers.getField(service, "recording");
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

    private static TextView findText(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = findText(group.getChildAt(i), text);
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

    /** The style chip's sheet (the chip itself, which a status line can stand in for while Chinese loads). */
    private void openSheet() {
        ReflectionHelpers.callInstanceMethod(service, "toggleStyleSheet");
        idle();
    }

    private String chip() {
        TextView chip = ReflectionHelpers.getField(service, "styleChip");
        return chip.getContentDescription().toString();
    }

    /** The sheet's "On the bar" chips, in order, by their spoken names. */
    private List<String> onTheBar() {
        View row = find("On the bar");
        assertNotNull("no On the bar row", row);
        List<String> out = new ArrayList<>();
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            out.add(group.getChildAt(i).getContentDescription().toString().replace(", chosen", ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ the keyboard's style

    @Test public void theChineseKeyboardStartsOnItsOwnStyleAndEnglishOnTheDefault() {
        Prefs.chooseChineseKeyboardStyle(service, Prefs.PRESET_BUSINESS);
        assertEquals(Prefs.PRESET_CLEAN, choice().preset);
        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals(Prefs.PRESET_BUSINESS, choice().preset);
        assertTrue(chip(), chip().startsWith("Style: Work"));
        keyboard(InputMode.CHINESE_KEYPAD);
        assertEquals("nine-key shares it", Prefs.PRESET_BUSINESS, choice().preset);
        tap("Dictate");
        assertTrue(recording());
        assertEquals(Prefs.PRESET_BUSINESS, choice().preset);
        tap("Cancel recording");
        keyboard(InputMode.ENGLISH);
        assertEquals(Prefs.PRESET_CLEAN, choice().preset);
        assertTrue(chip(), chip().startsWith("Style: Clean"));
    }

    @Test public void withoutASettingTheChineseKeyboardUsesTheEnglishStyle() {
        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals(Prefs.PRESET_CLEAN, choice().preset);
        tap("Dictate");
        assertEquals(Prefs.PRESET_CLEAN, choice().preset);
        tap("Cancel recording");
    }

    @Test public void theSpeechModelFollowsTheChineseKeyboardsStyle() throws Exception {
        publishMixed();
        Prefs.setSonioxApiKey(service, "test-soniox-key-0000");
        Prefs.setSpeechModelForStyle(service, MIXED, "{\"provider\":\"soniox\"}");
        Prefs.chooseChineseKeyboardStyle(service, MIXED);
        keyboard(InputMode.CHINESE_QWERTY);
        tap("Dictate");
        assertTrue(recording());
        SpeechModels.Resolved speech = ReflectionHelpers.getField(service, "recordingSpeech");
        assertEquals(Prefs.PROVIDER_SONIOX, speech.used.provider);
        assertEquals(MIXED, choice().preset);
        tap("Cancel recording");
        keyboard(InputMode.ENGLISH);
        tap("Dictate");
        speech = ReflectionHelpers.getField(service, "recordingSpeech");
        assertEquals("English: the app's model", Prefs.PROVIDER_OPENAI, speech.used.provider);
        tap("Cancel recording");
    }

    @Test public void aMissingWordListStyleFallsBackToEnglishUntilTheListsArrive() throws Exception {
        Prefs.chooseChineseKeyboardStyle(service, MIXED);
        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals("missing: the English style", Prefs.PRESET_CLEAN, choice().preset);
        publishMixed();
        assertEquals("the chip catches up when the lists arrive", MIXED, choice().preset);
        assertTrue(chip(), chip().startsWith("Style: Mixed example"));
        assertEquals("kept, never cleared", MIXED, Prefs.chineseKeyboardStyle(service));
    }

    @Test public void aFoldKeepsTheChineseKeyboardsStyle() {
        Prefs.chooseChineseKeyboardStyle(service, Prefs.PRESET_BUSINESS);
        keyboard(InputMode.CHINESE_QWERTY);
        root = service.onCreateInputView();
        idle();
        assertEquals(Prefs.PRESET_BUSINESS, choice().preset);
        assertTrue(chip(), chip().startsWith("Style: Work"));
    }

    @Test public void aSettingChangedWhileAwayShowsWhenTheKeyboardOpens() throws Exception {
        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals(Prefs.PRESET_CLEAN, choice().preset);
        Prefs.chooseChineseKeyboardStyle(service, Prefs.PRESET_CASUAL); // Settings, or over USB
        startField();
        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals(Prefs.PRESET_CASUAL, choice().preset);
    }

    @Test public void theBarLeadsWithTheEnglishThenTheChineseStyle() throws Exception {
        publishMixed();
        Prefs.chooseChineseKeyboardStyle(service, MIXED);
        openSheet();
        List<String> bar = onTheBar();
        assertEquals(bar.toString(), "Style Clean", bar.get(0));
        assertEquals(bar.toString(), "Style Mixed example", bar.get(1));
        assertEquals("on the bar once", 1, Collections.frequency(bar, "Style Mixed example"));
    }

    // ------------------------------------------------------------------ Style choice: Remember

    @Test public void rememberKeepsAPickForTheKeyboardItWasMadeOn() {
        openSheet();
        tap("Style Work");
        assertEquals("English keeps it", Prefs.PRESET_BUSINESS, Prefs.activePreset(service));
        assertNull("no Make default on Remember", find("Make default"));
        assertNotNull(findText(root, "Kept for the English keyboard"));
        tap("Close styles");
        tap("Dictate");
        tap("Cancel recording");
        assertEquals("still Work after the message", Prefs.PRESET_BUSINESS, choice().preset);

        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals("Same as English follows it", Prefs.PRESET_BUSINESS, choice().preset);
        openSheet();
        assertNotNull(findText(root, "Kept for the Chinese keyboard"));
        tap("Style Friends");
        assertEquals(Prefs.PRESET_CASUAL, Prefs.chineseKeyboardStyle(service));
        assertEquals("English untouched", Prefs.PRESET_BUSINESS, Prefs.activePreset(service));
        tap("Close styles");

        keyboard(InputMode.ENGLISH);
        assertEquals(Prefs.PRESET_BUSINESS, choice().preset);
        keyboard(InputMode.CHINESE_KEYPAD);
        assertEquals(Prefs.PRESET_CASUAL, choice().preset);
    }

    @Test public void aRememberedPickOutlastsTheKeyboardHiding() {
        openSheet();
        tap("Style Friends");
        service.onWindowHidden();
        idle();
        assertEquals(Prefs.PRESET_CASUAL, choice().preset);
        assertEquals(Prefs.PRESET_CASUAL, Prefs.activePreset(service));
    }

    @Test public void pickingTheEnglishStyleOnChineseLeavesItSameAsEnglish() {
        keyboard(InputMode.CHINESE_QWERTY);
        openSheet();
        tap("Style Clean, chosen");
        assertEquals("still Same as English", "", Prefs.chineseKeyboardStyle(service));
        tap("Close styles");
        keyboard(InputMode.ENGLISH);
        openSheet();
        tap("Style Work");
        tap("Close styles");
        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals("and so follows a new English style", Prefs.PRESET_BUSINESS, choice().preset);
    }

    @Test public void aPickDuringARecordingIsUsedAndKept() {
        keyboard(InputMode.CHINESE_QWERTY);
        tap("Dictate");
        tap("Style Work");
        assertEquals(Prefs.PRESET_BUSINESS, choice().preset);
        assertEquals("kept for the keyboard it started on", Prefs.PRESET_BUSINESS, Prefs.chineseKeyboardStyle(service));
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(service));
        tap("Cancel recording");
        assertEquals(Prefs.PRESET_BUSINESS, choice().preset);
    }

    @Test public void rememberKeepsTheExpressionAsTheStylesOwn() {
        openSheet();
        SeekBar slider = ReflectionHelpers.getField(service, "expressionSlider");
        SeekBar.OnSeekBarChangeListener listener = ReflectionHelpers.getField(slider, "mOnSeekBarChangeListener");
        slider.setProgress(Prefs.EXPRESSION_LIVELY);
        // A key or TalkBack step: a change from the user with no touch tracking around it.
        listener.onProgressChanged(slider, Prefs.EXPRESSION_LIVELY, true);
        idle();
        assertEquals(Prefs.EXPRESSION_LIVELY, Prefs.expressionForPreset(service, Prefs.PRESET_CLEAN));
    }

    // ------------------------------------------------------------------ Style choice: one message

    @Test public void oneMessageMakeDefaultSavesForTheKeyboardInUse() {
        Prefs.setStyleChoiceMode(service, KeyboardStyles.CHOICE_ONE_MESSAGE);
        Prefs.chooseChineseKeyboardStyle(service, Prefs.PRESET_BUSINESS);
        keyboard(InputMode.CHINESE_QWERTY);
        openSheet();
        tap("Style Friends");
        assertEquals("a one-off overrides it", Prefs.PRESET_CASUAL, choice().preset);
        assertEquals("nothing saved yet", Prefs.PRESET_BUSINESS, Prefs.chineseKeyboardStyle(service));
        tap("Make Friends · Natural your default for Chinese");
        assertEquals(Prefs.PRESET_CASUAL, Prefs.chineseKeyboardStyle(service));
        assertEquals("English untouched", Prefs.PRESET_CLEAN, Prefs.activePreset(service));
        tap("Close styles");

        keyboard(InputMode.ENGLISH);
        assertEquals(Prefs.PRESET_CLEAN, choice().preset);
        openSheet();
        tap("Style Work");
        assertNotNull(findText(root, "Make default for English"));
        tap("Make Work · Natural your default for English");
        assertEquals(Prefs.PRESET_BUSINESS, Prefs.activePreset(service));
        assertEquals(Prefs.PRESET_CASUAL, Prefs.chineseKeyboardStyle(service));
    }

    @Test public void oneMessageAPickLastsOneMessageAndSurvivesAKeyboardSwitch() {
        Prefs.setStyleChoiceMode(service, KeyboardStyles.CHOICE_ONE_MESSAGE);
        Prefs.chooseChineseKeyboardStyle(service, Prefs.PRESET_BUSINESS);
        openSheet();
        tap("Style Friends");
        tap("Close styles");
        keyboard(InputMode.CHINESE_QWERTY);
        assertEquals("the one-off stays", Prefs.PRESET_CASUAL, choice().preset);
        tap("Dictate");
        assertEquals(Prefs.PRESET_CASUAL, choice().preset);
        tap("Cancel recording");
        assertEquals("then the Chinese keyboard's style", Prefs.PRESET_BUSINESS, choice().preset);
        assertEquals(Prefs.PRESET_CLEAN, Prefs.activePreset(service));
    }

    // ------------------------------------------------------------------ word list off

    @Test public void aStyleWithItsWordListOffGetsNoWordsButKeepsCorrections() {
        Prefs.saveUserPhraseReplacements(service,
                Collections.singletonList(new PhraseReplacement("zylo font", "Zylophant")));
        Prefs.chooseChineseKeyboardStyle(service, Prefs.PRESET_BUSINESS);
        Prefs.setStyleWordsOn(service, Prefs.PRESET_BUSINESS, false);

        tap("Dictate");
        WordLists.DictationWords words = ReflectionHelpers.getField(service, "recordingWords");
        assertTrue("English: the words go", words.keywords.contains("Zylophant"));
        tap("Cancel recording");

        keyboard(InputMode.CHINESE_QWERTY);
        tap("Dictate");
        words = ReflectionHelpers.getField(service, "recordingWords");
        assertTrue(words.keywords.isEmpty());
        assertEquals("", words.prompt);
        assertTrue(words.contextTerms.isEmpty());
        assertTrue(words.tidyWords.words.isEmpty());
        boolean kept = false;
        for (PhraseReplacement correction : words.corrections) {
            kept |= "Zylophant".equals(correction.to);
        }
        assertTrue("corrections still apply", kept);
        tap("Cancel recording");
    }
}
