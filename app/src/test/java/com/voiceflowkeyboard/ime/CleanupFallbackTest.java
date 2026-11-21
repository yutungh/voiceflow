package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tidy-up, drift check, one strict retry, then the user's own words with the tidy-up
 * held back for "Use cleaned". The model is replaced by scripted answers.
 */
public class CleanupFallbackTest {
    private static final String SPOKEN = "So, um, 我觉得 we should 先 finish the QBR deck, because, 那个, 他们 need it by Friday.";
    private static final String GOOD = "我觉得 we should 先 finish the QBR deck, because 他们 need it by Friday.";
    private static final String DRIFTED = "我觉得 we should 先 finish the QBR deck，因为 他们 need it by Friday.";

    /** Answers in order and records whether each call was strict. */
    private static final class Script implements TransformClient.Attempt {
        final List<String> answers;
        final List<Boolean> strictCalls = new ArrayList<>();

        Script(String... answers) {
            this.answers = new ArrayList<>(Arrays.asList(answers));
        }

        @Override
        public String run(boolean strict) {
            strictCalls.add(strict);
            return answers.remove(0);
        }
    }

    @Test
    public void aCleanFirstAnswerIsUsedWithoutARetry() throws Exception {
        Script model = new Script(GOOD);
        TransformClient.Outcome outcome = TransformClient.checkedTransform(SPOKEN, false, model);
        assertEquals(GOOD, outcome.text);
        assertFalse(outcome.fellBack());
        assertFalse(outcome.retried);
        assertTrue(outcome.mixed);
        assertEquals(Arrays.asList(false), model.strictCalls);
    }

    @Test
    public void aDriftedAnswerGetsExactlyOneStrictRetry() throws Exception {
        Script model = new Script(DRIFTED, GOOD);
        TransformClient.Outcome outcome = TransformClient.checkedTransform(SPOKEN, false, model);
        assertEquals(GOOD, outcome.text);
        assertFalse(outcome.fellBack());
        assertTrue(outcome.retried);
        assertEquals(Arrays.asList(false, true), model.strictCalls);
    }

    @Test
    public void twoDriftedAnswersKeepHisWordsAndHoldTheTidyUpBack() throws Exception {
        Script model = new Script(DRIFTED, DRIFTED);
        TransformClient.Outcome outcome = TransformClient.checkedTransform(SPOKEN, false, model);
        assertEquals(SPOKEN, outcome.text);
        assertEquals(DRIFTED, outcome.heldBack);
        assertTrue(outcome.fellBack());
        assertEquals(DriftCheck.REASON_HAN_ADDED, outcome.fallbackReason);
        // Never a third call, and never a blocking question.
        assertEquals(2, model.strictCalls.size());
    }

    @Test
    public void theFallbackNeverStripsFillersByList() throws Exception {
        TransformClient.Outcome outcome = TransformClient.checkedTransform(SPOKEN, false, new Script(DRIFTED, DRIFTED));
        // 那个 can be a real word, so the user's words are kept exactly as transcribed.
        assertTrue(outcome.text.contains("那个"));
    }

    @Test
    public void funStylesAreNeverChecked() throws Exception {
        Script model = new Script("Arr, we be finishin' the deck by Friday.");
        TransformClient.Outcome outcome = TransformClient.checkedTransform(SPOKEN, true, model);
        assertEquals("Arr, we be finishin' the deck by Friday.", outcome.text);
        assertFalse(outcome.fellBack());
        assertEquals(1, model.strictCalls.size());
    }

    @Test
    public void englishSpeechThatCameBackInChineseIsRetriedWithTheRuleRestated() {
        String input = DictationText.transcriptInput("Milo, send the Pinewood notes", true, true);
        assertTrue(input.startsWith(DictationText.STRICT_LANGUAGE_NOTE));
        assertTrue(input.endsWith("Transcript:\nMilo, send the Pinewood notes"));
        assertEquals("Transcript:\nMilo, send the Pinewood notes",
                DictationText.transcriptInput("Milo, send the Pinewood notes", true, false));
    }

    @Test
    public void countersRecordMixedRetriesAndFallbacksByReason() {
        UpdateEvidence evidence = UpdateEvidence.fresh(44, 1000L);
        evidence.recordCleanup(true, false, "");
        evidence.recordCleanup(true, true, "");
        evidence.recordCleanup(true, true, DriftCheck.REASON_HAN_ADDED);
        evidence.recordCleanup(false, false, "");
        assertEquals(3, evidence.mixedDictations);
        assertEquals(2, evidence.driftRetries);
        assertEquals(1, evidence.fallbackCount());
        assertEquals(Integer.valueOf(1), evidence.fallbacks.get(DriftCheck.REASON_HAN_ADDED));
    }
}
