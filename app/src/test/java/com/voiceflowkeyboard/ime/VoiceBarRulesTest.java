package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The pure rules behind the new keyboard: which bar shows, one-off styles, pins, colours. */
public class VoiceBarRulesTest {
    private static VoiceBar.State state() {
        return new VoiceBar.State();
    }

    // ------------------------------------------------------------------ VoiceBar

    @Test public void idleWhenNothingIsGoingOn() {
        assertEquals(VoiceBar.Mode.IDLE, VoiceBar.mode(state()));
    }

    @Test public void recordingAndWorkingOutrankEverything() {
        VoiceBar.State s = state();
        s.composingCandidates = true;
        s.insertedFresh = true;
        s.keptWords = true;
        s.hasSuggestions = true;
        s.message = true;
        s.historyMode = true;
        s.recording = true;
        assertEquals(VoiceBar.Mode.LISTENING, VoiceBar.mode(s));
        s.recording = false;
        s.processing = true;
        assertEquals(VoiceBar.Mode.WORKING, VoiceBar.mode(s));
        s.processing = false;
        s.retoneMode = true;
        assertEquals(VoiceBar.Mode.RETONE, VoiceBar.mode(s));
        s.retoneMode = false;
        assertEquals(VoiceBar.Mode.HISTORY, VoiceBar.mode(s));
    }

    @Test public void pinyinCandidatesBeatTheInsertedBarAndSuggestions() {
        VoiceBar.State s = state();
        s.composingCandidates = true;
        s.insertedFresh = true;
        s.hasSuggestions = true;
        assertEquals(VoiceBar.Mode.CANDIDATES, VoiceBar.mode(s));
    }

    @Test public void keptWordsBeatsInsertedWhichBeatsAMessageWhichBeatsSuggestions() {
        VoiceBar.State s = state();
        s.keptWords = true;
        s.insertedFresh = true;
        s.message = true;
        s.hasSuggestions = true;
        assertEquals(VoiceBar.Mode.KEPT_WORDS, VoiceBar.mode(s));
        s.keptWords = false;
        assertEquals(VoiceBar.Mode.INSERTED, VoiceBar.mode(s));
        s.insertedFresh = false;
        assertEquals("a message shows in the idle bar", VoiceBar.Mode.IDLE, VoiceBar.mode(s));
        s.message = false;
        assertEquals(VoiceBar.Mode.SUGGESTIONS, VoiceBar.mode(s));
    }

    @Test public void routineStatesAreNotAnnouncedAsMessages() {
        for (String routine : new String[]{"Ready", "Shift on", "Caps lock", "Symbols", "Numbers", "Emoji", "Pasted", "", "Recording: Clean"}) {
            assertTrue(routine, VoiceBar.routineStatus(routine));
        }
        assertFalse(VoiceBar.routineStatus("Voice disabled in this field"));
        assertFalse(VoiceBar.routineStatus("Not inserted: you moved to another field"));
    }

    @Test public void labelsAndTimer() {
        assertEquals("Clean · Natural", VoiceBar.styleLabel("Clean", Prefs.EXPRESSION_NATURAL));
        assertEquals("Listening", VoiceBar.listeningLabel(false, false, false));
        assertEquals("Translating", VoiceBar.listeningLabel(true, false, false));
        assertEquals("Creating", VoiceBar.listeningLabel(false, true, false));
        assertEquals("Instruction", VoiceBar.listeningLabel(false, false, true));
        assertEquals("0:07", VoiceBar.elapsed(7_400));
        assertEquals("1:05", VoiceBar.elapsed(65_000));
        assertEquals("0:00", VoiceBar.elapsed(-5));
    }

    // --------------------------------------------------------------- StyleChoice

    @Test public void aPickIsForOneMessageAndRecordingKeepsIt() {
        StyleChoice choice = new StyleChoice("clean", Prefs.EXPRESSION_NATURAL);
        choice.pick("casual", Prefs.EXPRESSION_LIVELY);
        assertTrue(choice.oneOff);
        choice.startRecording("clean", Prefs.EXPRESSION_NATURAL);
        assertEquals("casual", choice.preset);
        assertEquals(Prefs.EXPRESSION_LIVELY, choice.expression);
        choice.reset("clean", Prefs.EXPRESSION_NATURAL);
        assertEquals("clean", choice.preset);
        assertFalse(choice.oneOff);
    }

    @Test public void withoutAPickRecordingStartsFromTheSavedDefault() {
        StyleChoice choice = new StyleChoice("clean", Prefs.EXPRESSION_NATURAL);
        choice.startRecording("business", Prefs.EXPRESSION_SUBTLE);
        assertEquals("business", choice.preset);
        assertEquals(Prefs.EXPRESSION_SUBTLE, choice.expression);
    }

    @Test public void anExpressionChangeIsAOneOffAndIsClamped() {
        StyleChoice choice = new StyleChoice("clean", Prefs.EXPRESSION_NATURAL);
        choice.setExpression(99);
        assertTrue(choice.oneOff);
        assertEquals(Prefs.EXPRESSION_EXPRESSIVE, choice.expression);
        assertTrue(choice.differsFrom("clean", Prefs.EXPRESSION_NATURAL));
        choice.makeDefault();
        assertFalse(choice.oneOff);
    }

    // ----------------------------------------------------------------- StylePins

    @Test public void withNoChoiceTheEverydayStylesArePinnedNotTheFunOnes() {
        List<String> all = Arrays.asList("clean", "casual", "business", "family", "fun_pirate", "partner", "custom_a", "custom_b");
        List<String> fun = Collections.singletonList("fun_pirate");
        assertEquals(Arrays.asList("clean", "casual", "business", "family", "partner", "custom_a"),
                StylePins.onBar(all, fun, null, null));
    }

    @Test public void storedPinsKeepTheirOrderSkipGoneStylesAndAlwaysShowTheChosenOne() {
        List<String> all = Arrays.asList("clean", "casual", "business", "fun_pirate");
        List<String> fun = Collections.singletonList("fun_pirate");
        assertEquals(Arrays.asList("business", "clean"),
                StylePins.onBar(all, fun, Arrays.asList("business", "deleted", "clean"), null));
        assertEquals(Arrays.asList("business", "clean", "fun_pirate"),
                StylePins.onBar(all, fun, Arrays.asList("business", "clean"), "fun_pirate"));
    }

    @Test public void pinningTogglesOneStyle() {
        assertEquals(Arrays.asList("clean", "casual"), StylePins.toggle(Collections.singletonList("clean"), "casual"));
        assertEquals(Collections.singletonList("clean"), StylePins.toggle(Arrays.asList("clean", "casual"), "casual"));
    }

    // -------------------------------------------------------------------- colour

    @Test public void textMeetsContrastInBothThemes() {
        for (boolean dark : new boolean[]{false, true}) {
            Palette p = Palette.of(dark);
            String theme = dark ? "dark " : "light ";
            assertAa(theme + "fg on bg", p.fg, p.bg);
            assertAa(theme + "fg on surface", p.fg, p.surface);
            assertAa(theme + "key labels", p.keyFg, p.key);
            assertAa(theme + "function key labels", p.keyFg, p.keyFn);
            assertAa(theme + "muted on bg", p.muted, p.bg);
            assertAa(theme + "muted on keyboard", p.muted, p.ime);
            assertAa(theme + "subtle on bg", p.subtle, p.bg);
            assertAa(theme + "subtle on surface", p.subtle, p.surface);
            assertAa(theme + "subtle on keyboard", p.subtle, p.ime);
            assertAa(theme + "tonal ink", p.accentInk, p.accentTint);
            assertAa(theme + "accent on surface", p.accent, p.surface);
            assertAa(theme + "on accent", p.onAccent, p.accent);
            assertAa(theme + "ok tile", p.tileOkFg, p.tileOk);
            assertAa(theme + "indigo tile", p.tileIndFg, p.tileInd);
            assertAa(theme + "warn tile", p.tileWarnFg, p.tileWarn);
            assertAa(theme + "accent tile", p.tileAccFg, p.tileAcc);
            // Non-text marks need 3:1.
            assertTrue(theme + "record dot", Palette.contrast(p.record, p.ime) >= 3.0);
            assertTrue(theme + "success tick", Palette.contrast(p.success, p.ime) >= 3.0);
            assertTrue(theme + "info mark", Palette.contrast(p.info, p.ime) >= 3.0);
        }
    }

    private static void assertAa(String what, int text, int background) {
        double ratio = Palette.contrast(text, background);
        assertTrue(what + " is " + String.format(java.util.Locale.ROOT, "%.2f", ratio), ratio >= 4.5);
    }
}
