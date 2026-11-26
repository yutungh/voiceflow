package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.res.Configuration;
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
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.List;

/**
 * The language key in the running keyboard, 0.8.8: English is the main
 * keyboard, a tap toggles to the second one, a hold opens the list, the
 * setting's cycle and list behaviours, a new field starting in English, and
 * a composition never lost on a switch or a fold.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LanguageKeyServiceTest {

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
        Prefs.setChineseInputEnabled(service, true);
        Prefs.setChineseLayout(service, Prefs.CHINESE_LAYOUT_KEYPAD);
        // These cases are the 0.8.8 opening (always English), now the "Always English" setting.
        // The 0.9.3 default, the last keyboard used, is tested at the end.
        Prefs.setKeyboardOpenOn(service, KeyboardOpening.OPEN_ENGLISH);
        service.connection = new BaseInputConnection(new View(service), true);
        service.editor = editor();
        root = service.onCreateInputView();
        startField(false);
    }

    @After public void tearDown() { controller.destroy(); }

    private EditorInfo editor() {
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        info.packageName = "com.example.chat";
        info.fieldId = nextFieldId++;
        return info;
    }

    private void startField(boolean restarting) {
        service.onStartInput(service.editor, restarting);
        service.onStartInputView(service.editor, restarting);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private InputMode mode() {
        return ReflectionHelpers.getField(service, "inputMode");
    }

    private void tap() {
        ReflectionHelpers.callInstanceMethod(service, "onLanguageKeyTap");
    }

    private TextView languageKey() {
        View key = findDescriptionStart(root, "Keyboard: ");
        assertNotNull("no language key", key);
        return (TextView) key;
    }

    private boolean pickerOpen() {
        return ReflectionHelpers.<Boolean>callInstanceMethod(service, "keyboardPickerOpen");
    }

    private void pick(InputMode mode) {
        View row = findDescriptionStart(root, LanguageKey.name(mode));
        assertNotNull("no row for " + mode, row);
        row.performClick();
    }

    private void waitForEngine(InputMode mode) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            PinyinEngine engine = ReflectionHelpers.getField(service, "pinyinEngine");
            Object session = ReflectionHelpers.getField(service, "pinyinSession");
            if (engine != null && engine.isReady(mode) && session != null) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the pinyin engine never became ready");
    }

    private void typeDigits(String digits) {
        for (char digit : digits.toCharArray()) {
            ReflectionHelpers.callInstanceMethod(service, "commitKey",
                    ClassParameter.from(String.class, String.valueOf(digit)));
        }
    }

    private String text() {
        return service.connection.getEditable().toString();
    }

    private String composing() {
        android.text.Editable editable = service.connection.getEditable();
        int start = BaseInputConnection.getComposingSpanStart(editable);
        int end = BaseInputConnection.getComposingSpanEnd(editable);
        return start < 0 || end < 0 ? "" : editable.subSequence(start, end).toString();
    }

    private static View findDescriptionStart(View view, String prefix) {
        if (view.getVisibility() != View.VISIBLE) return null;
        CharSequence description = view.getContentDescription();
        if (description != null && description.toString().startsWith(prefix)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findDescriptionStart(group.getChildAt(i), prefix);
                if (match != null) return match;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ toggle

    @Test public void theKeyboardOpensInEnglish() {
        assertEquals(InputMode.ENGLISH, mode());
        assertEquals("EN", languageKey().getText().toString());
    }

    @Test public void aTapTogglesEnglishAndTheSecondKeyboard() {
        tap();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        assertEquals("九", languageKey().getText().toString());
        tap();
        assertEquals(InputMode.ENGLISH, mode());
        tap();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }

    @Test public void aHoldOpensTheListAndThePickIsSaved() {
        assertTrue(languageKey().performLongClick());
        assertTrue(pickerOpen());
        pick(InputMode.CHINESE_QWERTY);
        assertFalse(pickerOpen());
        assertEquals(InputMode.CHINESE_QWERTY, mode());
        assertEquals(Prefs.CHINESE_LAYOUT_QWERTY, Prefs.chineseLayout(service));
        assertEquals("中", languageKey().getText().toString());
        // The toggle now goes to the full keyboard.
        tap();
        assertEquals(InputMode.ENGLISH, mode());
        tap();
        assertEquals(InputMode.CHINESE_QWERTY, mode());
    }

    @Test public void pickingEnglishLeavesTheSecondKeyboardAlone() {
        tap();
        languageKey().performLongClick();
        pick(InputMode.ENGLISH);
        assertEquals(InputMode.ENGLISH, mode());
        assertEquals(Prefs.CHINESE_LAYOUT_KEYPAD, Prefs.chineseLayout(service));
    }

    @Test public void theListClosesWithoutTypingAnything() {
        languageKey().performLongClick();
        assertTrue(pickerOpen());
        View picker = ReflectionHelpers.getField(service, "keyboardPicker");
        picker.performClick();
        assertFalse(pickerOpen());
        assertEquals(InputMode.ENGLISH, mode());
        assertEquals("", text());
        // Back closes it too.
        languageKey().performLongClick();
        service.onKeyDown(android.view.KeyEvent.KEYCODE_BACK,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
        service.onKeyUp(android.view.KeyEvent.KEYCODE_BACK,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
        assertFalse(pickerOpen());
    }

    // ------------------------------------------------------ cycle and list

    @Test public void cycleVisitsEveryKeyboardAndKeepsTheSecondOne() {
        Prefs.setLanguageKeyBehaviour(service, LanguageKey.Behaviour.CYCLE);
        tap();
        assertEquals(InputMode.CHINESE_QWERTY, mode());
        tap();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        tap();
        assertEquals(InputMode.ENGLISH, mode());
        assertEquals("cycling saves nothing", Prefs.CHINESE_LAYOUT_KEYPAD, Prefs.chineseLayout(service));
    }

    @Test public void reopeningTheSameFieldDoesNotUndoACycle() {
        Prefs.setLanguageKeyBehaviour(service, LanguageKey.Behaviour.CYCLE);
        tap();
        assertEquals(InputMode.CHINESE_QWERTY, mode());
        startField(true);
        assertEquals(InputMode.CHINESE_QWERTY, mode());
    }

    @Test public void listMakesATapOpenTheList() {
        Prefs.setLanguageKeyBehaviour(service, LanguageKey.Behaviour.LIST);
        tap();
        assertTrue(pickerOpen());
        assertEquals(InputMode.ENGLISH, mode());
        pick(InputMode.CHINESE_KEYPAD);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        assertFalse(pickerOpen());
    }

    @Test public void withChineseOffThereIsNoLanguageKeyAndATapStaysEnglish() {
        Prefs.setChineseInputEnabled(service, false);
        root = service.onCreateInputView();
        startField(false);
        assertNull(findDescriptionStart(root, "Keyboard: "));
        tap();
        assertEquals(InputMode.ENGLISH, mode());
    }

    // ------------------------------------------------- English on a new field

    @Test public void aNewFieldStartsInEnglish() {
        tap();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        service.editor = editor();
        service.connection = new BaseInputConnection(new View(service), true);
        startField(false);
        assertEquals(InputMode.ENGLISH, mode());
    }

    @Test public void theSameFieldRestartingKeepsTheKeyboard() {
        tap();
        // An app restarting input after a send, or re-binding the same field.
        startField(true);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        startField(false);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }

    @Test public void closingTheKeyboardBringsEnglishBack() {
        tap();
        service.onWindowHidden();
        service.onWindowShown();
        assertEquals(InputMode.ENGLISH, mode());
    }

    @Test public void aSettingsChangeMovesTheChineseKeyboardToTheNewLayout() {
        tap();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        Prefs.setChineseLayout(service, Prefs.CHINESE_LAYOUT_QWERTY);
        startField(true);
        assertEquals(InputMode.CHINESE_QWERTY, mode());
    }

    // ------------------------------------------------ nothing typed is lost

    @Test public void switchingMidWordCommitsTheWord() throws Exception {
        tap();
        waitForEngine(InputMode.CHINESE_KEYPAD);
        typeDigits("64426");
        assertEquals("ni'hao", composing());
        tap();
        assertEquals(InputMode.ENGLISH, mode());
        assertEquals("你好", text());
        assertEquals("", composing());
    }

    @Test public void openingAndClosingTheListKeepsTheWord() throws Exception {
        tap();
        waitForEngine(InputMode.CHINESE_KEYPAD);
        typeDigits("64426");
        languageKey().performLongClick();
        View picker = ReflectionHelpers.getField(service, "keyboardPicker");
        picker.performClick();
        assertEquals("ni'hao", composing());
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertTrue(session.isComposing());
    }

    /**
     * Closing the keyboard mid-word: Android calls onFinishInputView(false) and
     * ends the composing span, so the word is settled there and then; it
     * reopens in English with nothing left half-typed (Codex review).
     */
    @Test public void closingMidWordSettlesTheWordThenOpensInEnglish() throws Exception {
        tap();
        waitForEngine(InputMode.CHINESE_KEYPAD);
        typeDigits("64426");
        service.onFinishInputView(false);
        service.onWindowHidden();
        service.onStartInputView(service.editor, false);
        service.onWindowShown();
        assertEquals("你好", text());
        assertEquals("", composing());
        assertEquals(InputMode.ENGLISH, mode());
        // Typing on afterwards never repeats the word.
        ReflectionHelpers.callInstanceMethod(service, "commitKey", ClassParameter.from(String.class, "a"));
        assertTrue(text(), text().startsWith("你好") && !text().substring(2).contains("你好"));
    }

    @Test public void aNewFieldDuringADictationTakesEffectWhenItEnds() {
        tap();
        ReflectionHelpers.setField(service, "processing", true);
        service.editor = editor();
        service.onStartInput(service.editor, false);
        assertEquals("not while the text is on its way", InputMode.CHINESE_KEYPAD, mode());
        ReflectionHelpers.callInstanceMethod(service, "finishProcessingState",
                ClassParameter.from(String.class, "Inserted"));
        assertEquals(InputMode.ENGLISH, mode());
        // A same-field restart afterwards (an app after a send) changes nothing more.
        tap();
        startField(true);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }

    @Test public void aRestartOntoAnotherFieldIsTrackedSoItsFreshStartKeepsTheKeyboard() {
        tap();
        // A WebView moving to another of its fields restarts input...
        service.editor = editor();
        startField(true);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        // ...and a fresh start of that same field afterwards is not a new field.
        startField(false);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }

    private void rotate() {
        Configuration changed = new Configuration(service.getResources().getConfiguration());
        changed.orientation = changed.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        service.onConfigurationChanged(changed);
    }

    @Test public void aNewFieldRightAfterATurnStillStartsInEnglish() {
        tap();
        rotate();
        service.editor = editor();
        startField(false);
        assertEquals(InputMode.ENGLISH, mode());
    }

    @Test public void aHideThatBelongsToAFoldKeepsTheKeyboard() {
        tap();
        service.onWindowHidden();
        rotate();
        service.onWindowShown();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }

    @Test public void closingTheKeyboardDuringADictationBringsEnglishWhenItEnds() {
        tap();
        ReflectionHelpers.setField(service, "processing", true);
        service.onWindowHidden();
        service.onWindowShown();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        ReflectionHelpers.callInstanceMethod(service, "finishProcessingState",
                ClassParameter.from(String.class, "Inserted"));
        assertEquals(InputMode.ENGLISH, mode());
    }

    /** More's layout item offers the other layout from the one on screen, even after cycling past the saved one. */
    @Test public void theMoreMenuOffersTheOtherLayoutFromTheScreen() {
        Prefs.setLanguageKeyBehaviour(service, LanguageKey.Behaviour.CYCLE);
        Prefs.setChineseLayout(service, Prefs.CHINESE_LAYOUT_QWERTY);
        tap();
        tap();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        ReflectionHelpers.callInstanceMethod(service, "showOverflowMenu", ClassParameter.from(View.class, root));
        android.widget.PopupMenu menu = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu();
        android.view.MenuItem item = menu.getMenu().findItem(7);
        assertEquals("中文 全键 (full keyboard)", item.getTitle().toString());
        menu.getMenu().performIdentifierAction(7, 0);
        assertEquals(InputMode.CHINESE_QWERTY, mode());
    }

    @Test public void theListHidesTheKeysFromTalkBackWhileOpen() {
        View column = ReflectionHelpers.getField(service, "keyboardColumn");
        languageKey().performLongClick();
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, column.getImportantForAccessibility());
        View picker = ReflectionHelpers.getField(service, "keyboardPicker");
        picker.performClick();
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, column.getImportantForAccessibility());
    }

    @Test public void foldingKeepsTheKeyboardAndTheWord() throws Exception {
        tap();
        waitForEngine(InputMode.CHINESE_KEYPAD);
        typeDigits("64426");
        Configuration changed = new Configuration(service.getResources().getConfiguration());
        changed.orientation = changed.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        service.onConfigurationChanged(changed);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertTrue(session.isComposing());
    }

    @Test public void foldingWithNothingComposedKeepsTheKeyboardToo() {
        tap();
        Configuration changed = new Configuration(service.getResources().getConfiguration());
        changed.orientation = changed.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        service.onConfigurationChanged(changed);
        // What the framework does for a fold: a new view, input restarted on the same field.
        ReflectionHelpers.setField(service, "rebuildingForConfiguration", true);
        root = service.onCreateInputView();
        startField(true);
        ReflectionHelpers.setField(service, "rebuildingForConfiguration", false);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }

    // ------------------------------------------------------------ space bar

    @Test public void theSpaceBarCarriesNoLanguageName() {
        for (InputMode keyboard : LanguageKey.keyboards(true)) {
            ReflectionHelpers.callInstanceMethod(service, "setInputMode",
                    ClassParameter.from(InputMode.class, keyboard));
            assertSpaceBarsBlank(keyboard.toString());
            assertEquals(keyboard.keyLabel(), languageKey().getText().toString());
        }
        ReflectionHelpers.callInstanceMethod(service, "setInputMode",
                ClassParameter.from(InputMode.class, InputMode.ENGLISH));
        ReflectionHelpers.callInstanceMethod(service, "toggleSymbolsMode");
        assertSpaceBarsBlank("symbols");
    }

    private void assertSpaceBarsBlank(String where) {
        List<TextView> spaces = ReflectionHelpers.getField(service, "spaceButtons");
        assertFalse("no space bar on " + where, spaces.isEmpty());
        for (TextView space : spaces) {
            assertEquals("space bar on " + where, "", space.getText().toString());
            assertEquals("Space", space.getContentDescription().toString());
        }
    }

    // ------------------------------------- 0.9.3: open on the last keyboard used

    private void newField(int inputType) {
        service.editor = editor();
        service.editor.inputType = inputType;
        service.connection = new BaseInputConnection(new View(service), true);
        startField(false);
    }

    @Test public void byDefaultANewFieldOpensOnTheLastKeyboardUsed() {
        Prefs.setKeyboardOpenOn(service, KeyboardOpening.OPEN_LAST_USED);
        tap();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        newField(InputType.TYPE_CLASS_TEXT);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        // Closing and reopening the keyboard keeps it too.
        service.onWindowHidden();
        service.onWindowShown();
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
        // Back to English: the next field opens in English.
        tap();
        newField(InputType.TYPE_CLASS_TEXT);
        assertEquals(InputMode.ENGLISH, mode());
    }

    @Test public void aPasswordFieldOpensInEnglishAndIsNotRemembered() {
        Prefs.setKeyboardOpenOn(service, KeyboardOpening.OPEN_LAST_USED);
        tap();
        newField(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        assertEquals(InputMode.ENGLISH, mode());
        assertEquals(Prefs.CHINESE_LAYOUT_KEYPAD, Prefs.rememberedKeyboard(service));
        newField(InputType.TYPE_CLASS_TEXT);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }

    @Test public void theRememberedKeyboardSurvivesTheServiceRestarting() {
        Prefs.setKeyboardOpenOn(service, KeyboardOpening.OPEN_LAST_USED);
        tap();
        controller.destroy();
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        service.connection = new BaseInputConnection(new View(service), true);
        service.editor = editor();
        root = service.onCreateInputView();
        startField(false);
        assertEquals(InputMode.CHINESE_KEYPAD, mode());
    }
}