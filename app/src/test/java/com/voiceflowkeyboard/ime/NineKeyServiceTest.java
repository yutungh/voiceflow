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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The nine-key (九宫格) pad in the running keyboard, 0.8.7: typing digits,
 * the reading in the field, pinning a spelling from the side column, committing
 * a run word by word, 分词, 重输, delete, and English left as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NineKeyServiceTest {

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
        controller = Robolectric.buildService(TestService.class).create();
        service = controller.get();
        Prefs.setChineseInputEnabled(service, true);
        Prefs.setChineseLayout(service, Prefs.CHINESE_LAYOUT_KEYPAD);
        service.connection = new BaseInputConnection(new View(service), true);
        service.editor = editor();
        root = service.onCreateInputView();
        startField();
        ReflectionHelpers.callInstanceMethod(service, "setInputMode",
                ClassParameter.from(InputMode.class, InputMode.CHINESE_KEYPAD));
        waitForEngine();
    }

    @After public void tearDown() { controller.destroy(); }

    private EditorInfo editor() {
        EditorInfo info = new EditorInfo();
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        info.packageName = "com.example.chat";
        info.fieldId = nextFieldId++;
        return info;
    }

    private void startField() {
        service.onStartInput(service.editor, false);
        service.onStartInputView(service.editor, false);
    }

    /** The dictionary and the 9-key index load off the main thread. */
    private void waitForEngine() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            PinyinEngine engine = ReflectionHelpers.getField(service, "pinyinEngine");
            Object session = ReflectionHelpers.getField(service, "pinyinSession");
            if (engine != null && engine.isReady(InputMode.CHINESE_KEYPAD) && session != null) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the nine-key engine never became ready");
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

    private void type(String digits) {
        for (char digit : digits.toCharArray()) {
            View key = findDescription(root, digit + " " + lettersFor(digit));
            assertNotNull("no key for " + digit, key);
            key.performClick();
        }
    }

    private static String lettersFor(char digit) {
        return new String(PinyinKeypadLookup.lettersFor(digit)).toUpperCase(java.util.Locale.US);
    }

    private void tapText(String label) {
        View view = findText(root, label);
        assertNotNull("nothing reads " + label, view);
        view.performClick();
    }

    private void tapDescription(String description) {
        View view = findDescription(root, description);
        assertNotNull("nothing described as " + description, view);
        view.performClick();
    }

    private static View findText(View view, String label) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText()) && view.isClickable()) {
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

    private static View findDescription(View view, String description) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findDescription(group.getChildAt(i), description);
                if (match != null) return match;
            }
        }
        return null;
    }

    private TextView spellingLine() {
        return ReflectionHelpers.getField(service, "pinyinSpellingText");
    }

    // ------------------------------------------------------------------ typing

    @Test public void digitsReadAsPinyinInTheFieldAndOverTheCandidates() {
        type("64426");
        assertEquals("ni'hao", composing());
        assertEquals(View.VISIBLE, spellingLine().getVisibility());
        assertEquals("ni'hao", spellingLine().getText().toString());
        tapText("你好");
        assertEquals("你好", text());
        assertEquals("", composing());
        assertEquals(View.GONE, spellingLine().getVisibility());
    }

    /** 0.8.8: a run over several syllables offers the whole sentence first, as full-keyboard pinyin does. */
    @Test public void theFirstCandidateIsTheWholeSentence() {
        type("96744");
        assertEquals("wo'shi", composing());
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertEquals("我是", session.candidates().get(0).text);
        tapText("我是");
        assertEquals("我是", text());
        assertEquals("", composing());

        type(PinyinDigitIndex.project("zhongguoren"));
        tapText("中国人");
        assertEquals("我是中国人", text());
    }

    @Test public void spaceTakesTheWholeSentence() {
        type(PinyinDigitIndex.project("woshizhongguoren"));
        ReflectionHelpers.callInstanceMethod(service, "settlePinyin",
                ClassParameter.from(PinyinSession.SettleReason.class, PinyinSession.SettleReason.ACCEPT_TOP));
        assertEquals("我是中国人", text());
    }

    @Test public void spaceTakesTheTopReading() {
        type("94664486");
        assertEquals("zhong'guo", composing());
        ReflectionHelpers.callInstanceMethod(service, "settlePinyin",
                ClassParameter.from(PinyinSession.SettleReason.class, PinyinSession.SettleReason.ACCEPT_TOP));
        assertEquals("中国", text());
    }

    @Test public void aSpellingFromTheSideColumnIsPinned() {
        type("64");
        // ni leads, mi is there, and so are the letters.
        assertNotNull(findDescription(root, "Spell ni"));
        assertNotNull(findDescription(root, "Spell m"));
        tapDescription("Spell mi");
        assertEquals("mi'", composing());
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertEquals("米", session.candidates().get(0).text);
        tapText("米");
        assertEquals("米", text());
        // Done composing: the side column is punctuation again.
        assertNull(findDescription(root, "Spell ni"));
        assertNotNull(findText(root, "，"));
    }

    @Test public void choosingTheFirstWordLeavesTheRestComposing() {
        type("64426");
        tapText("你");
        assertEquals("你hao", text());
        assertEquals("hao", composing());
        tapText("好");
        assertEquals("你好", text());
        assertEquals("", composing());
    }

    @Test public void theOneKeyIsASyllableBreak() {
        type("94");
        tapDescription("Syllable break");
        type("26");
        // The break shows where it was typed (0.8.9: 一波 may lead, yi'bo).
        assertEquals(2, composing().indexOf('\''));
        assertNotNull(findText(root, "西安"));
        assertNull("先 is one syllable, the break says two", findText(root, "先"));
    }

    @Test public void theOneKeyTypesOneWhenNothingIsComposing() {
        tapDescription("Syllable break");
        assertEquals("1", text());
    }

    @Test public void reenterClearsWhatWasTyped() {
        service.connection.commitText("好的", 1);
        type("7426");
        tapDescription("Clear what you typed");
        assertEquals("好的", text());
        assertEquals("", composing());
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertFalse(session.isComposing());
    }

    @Test public void deleteShortensTheDigitsThenTheText() {
        service.connection.commitText("好", 1);
        type("64");
        ReflectionHelpers.callInstanceMethod(service, "deleteOne");
        assertEquals("好", text().substring(0, 1));
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertEquals("6", session.rawText());
        ReflectionHelpers.callInstanceMethod(service, "deleteOne");
        assertEquals("好", text());
        assertEquals("", composing());
        ReflectionHelpers.callInstanceMethod(service, "deleteOne");
        assertEquals("", text());
    }

    @Test public void sideColumnPunctuationSettlesFirst() {
        // While composing the column offers spellings, so punctuation arrives
        // through the same path from 符 or a hardware key: it settles first.
        type("426");
        ReflectionHelpers.callInstanceMethod(service, "commitKeypadPunctuation",
                ClassParameter.from(String.class, "，"));
        assertEquals("好，", text());
        tapText("…");
        assertEquals("好，……", text());
        tapText("？");
        assertEquals("好，……？", text());
    }

    @Test public void lettersCanBeTypedAsLatin() {
        type("65");
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        String letters = session.composingText().replace("'", "");
        View latin = findDescription(root, "Type " + letters + " as Latin text");
        assertNotNull(latin);
        latin.performClick();
        assertEquals(letters, text());
    }

    // ---------------------------------------------------------------- English

    @Test public void englishIsTheQwertyKeyboardAsBefore() {
        ReflectionHelpers.callInstanceMethod(service, "onLanguageKeyTap");
        assertEquals(InputMode.ENGLISH, ReflectionHelpers.getField(service, "inputMode"));
        assertNull(findDescription(root, "Syllable break"));
        assertTrue(findText(root, "q") != null || findText(root, "Q") != null);
        ReflectionHelpers.callInstanceMethod(service, "commitKey", ClassParameter.from(String.class, "h"));
        ReflectionHelpers.callInstanceMethod(service, "commitKey", ClassParameter.from(String.class, "i"));
        assertTrue(text(), text().endsWith("hi") || text().endsWith("Hi"));
        // And back to the nine-key pad, remembered.
        ReflectionHelpers.callInstanceMethod(service, "onLanguageKeyTap");
        assertEquals(InputMode.CHINESE_KEYPAD, ReflectionHelpers.getField(service, "inputMode"));
    }

    @Test public void theMoreMenuSwitchesBetweenTheChineseLayouts() {
        ReflectionHelpers.callInstanceMethod(service, "chooseKeyboard",
                ClassParameter.from(InputMode.class, InputMode.CHINESE_QWERTY));
        assertEquals(Prefs.CHINESE_LAYOUT_QWERTY, Prefs.chineseLayout(service));
        assertNotNull(findText(root, "q"));
        assertNull(findDescription(root, "Syllable break"));
    }

    // ------------------------------------------------------------ fold, unfold

    @Test public void foldingMidWordKeepsTheComposition() {
        type("64426");
        // What onConfigurationChanged does around the framework's rebuild: the
        // view is created again and input restarts on the same field.
        ReflectionHelpers.setField(service, "rebuildingForConfiguration", true);
        root = service.onCreateInputView();
        service.onStartInput(service.editor, true);
        service.onStartInputView(service.editor, true);
        ReflectionHelpers.setField(service, "rebuildingForConfiguration", false);
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertTrue(session.isComposing());
        assertEquals("ni'hao", composing());
        assertNotNull(findText(root, "你好"));
        tapText("你好");
        assertEquals("你好", text());
    }

    @Test public void theRealConfigurationChangeKeepsTheCompositionToo() {
        type("64426");
        Configuration changed = new Configuration(service.getResources().getConfiguration());
        changed.orientation = changed.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
        service.onConfigurationChanged(changed);
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertTrue(session.isComposing());
        assertFalse(ReflectionHelpers.<Boolean>getField(service, "rebuildingForConfiguration"));
    }

    @Test public void anotherFieldStillDropsTheComposition() {
        type("64426");
        service.editor = editor();
        service.connection = new BaseInputConnection(new View(service), true);
        startField();
        PinyinSession session = ReflectionHelpers.getField(service, "pinyinSession");
        assertTrue(session == null || !session.isComposing());
        assertEquals("", text());
        // 0.9.3: the new field opens on the keyboard last used (0.8.8 to 0.9.2: English,
        // still the "Always English" setting, LanguageKeyServiceTest).
        assertEquals(InputMode.CHINESE_KEYPAD, ReflectionHelpers.getField(service, "inputMode"));
    }

    @Test @Config(qualifiers = "w752dp-h835dp")
    public void onTheFoldInnerScreenThePadIsCentredAtThumbWidth() {
        RuntimeEnvironment.setQualifiers("w752dp-h835dp");
        root = service.onCreateInputView();
        startField();
        View pad = findDescription(root, "Syllable break");
        assertNotNull(pad);
        // Walk up to the pad: the horizontal block whose parent is the panel.
        View panel = ReflectionHelpers.getField(service, "keyboardPanel");
        View block = pad;
        while (block.getParent() instanceof View && block.getParent() != panel) {
            block = (View) block.getParent();
        }
        float density = service.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(KeyboardGeometry.KEYPAD_MAX_WIDTH_DP * density), block.getLayoutParams().width);
    }

    @Test @Config(qualifiers = "w752dp-h835dp")
    public void theSplitSwitchTurnsTheSplitOffOnTheFold() {
        Object metrics = ReflectionHelpers.getField(service, "metrics");
        assertTrue(ReflectionHelpers.<Boolean>getField(metrics, "split"));
        Prefs.setSplitLargeScreens(service, false);
        // Picked up the next time the keyboard opens.
        startField();
        metrics = ReflectionHelpers.getField(service, "metrics");
        assertFalse(ReflectionHelpers.<Boolean>getField(metrics, "split"));
        Prefs.setSplitLargeScreens(service, true);
    }
}
