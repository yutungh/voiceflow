package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The language-drift check, on the shapes real tidy-ups produced on mixed speech
 * (2026-11-19 runs, names invented) and on the legitimate edits it must let
 * through: fillers in both languages, false starts, abandoned self-corrections.
 */
public class DriftCheckTest {
    private static final String MOM = "So, um, I talked to Febe and she's like, 那个，她说周末可能不来了, because, like, "
            + "her back is, you know, still hurting. I mean, 就是，我们可以, like, go visit her instead? Maybe Sunday afternoon.";
    private static final String FLIGHT = "Okay so, like, the flight to Pinewood is on Friday, 不对，是 Saturday morning, "
            + "因为 Friday 的票太贵了。And then, you know, 我们 can rent a car at the airport, 然后 drive to the hotel. 那个，你觉得 that works?";
    private static final String MEETING = "呃，那个，我今天下午要去 QBR meeting，就是，可能会 late 一点。Um, 你可以帮我 pick up the kids 吗？然后，嗯，晚上我们 order pizza 吧。";

    // --- legitimate tidy-ups pass ---

    @Test
    public void deletingFillersInBothLanguagesIsNotDrift() {
        String cleaned = "I talked to Febe and she's like, 她说周末可能不来了, because her back is still hurting. "
                + "我们可以 go visit her instead? Maybe Sunday afternoon.";
        DriftCheck.Result result = DriftCheck.check(MOM, cleaned);
        assertFalse(result.drift);
        assertTrue(result.mixed);
    }

    @Test
    public void droppingTheAbandonedHalfOfACorrectionIsNotDrift() {
        String cleaned = "The flight to Pinewood is on Saturday morning，因为 Friday 的票太贵了。And then 我们 can rent a car "
                + "at the airport，然后 drive to the hotel。你觉得 that works?";
        assertFalse(DriftCheck.check(FLIGHT, cleaned).drift);
    }

    @Test
    public void chineseLedSpeechWithFillersRemovedIsNotDrift() {
        String cleaned = "我今天下午要去 QBR meeting，可能会 late 一点。你可以帮我 pick up the kids 吗？晚上我们 order pizza 吧。";
        assertFalse(DriftCheck.check(MEETING, cleaned).drift);
    }

    @Test
    public void englishOnlyRewritesAreNeverDrift() {
        String spoken = "Hey so, like, are you guys still down for dinner on Saturday? I think seven works? Or, no wait, seven thirty.";
        String cleaned = "Hey, are you guys still down for dinner on Saturday? I think seven thirty works.";
        DriftCheck.Result result = DriftCheck.check(spoken, cleaned);
        assertFalse(result.drift);
        assertFalse(result.mixed);
    }

    @Test
    public void pureChineseEditedInChineseIsNotDrift() {
        assertFalse(DriftCheck.check("呃，那个，我们明天开会吧", "我们明天开会吧。").drift);
        // Rewording within Chinese (adding characters) is a normal tidy-up there.
        assertFalse(DriftCheck.check("我们明天那个开会", "我们明天下午开会。").drift);
    }

    @Test
    public void caseAndApostropheVariantsAreNotAdditions() {
        String spoken = "ok 我们 go, it’s fine 因为 the QBR moved";
        String cleaned = "Okay, 我们 go. It's fine 因为 the QBR moved.";
        assertFalse(DriftCheck.check(spoken, cleaned).drift);
    }

    // --- translated clauses are caught ---

    @Test
    public void englishConnectorTurnedIntoChineseIsDrift() {
        String drifted = "I talked to Febe and she's like, 她说周末可能不来了，因为 her back is still hurting. "
                + "我们可以 go visit her instead? Maybe Sunday afternoon.";
        DriftCheck.Result result = DriftCheck.check(MOM, drifted);
        assertTrue(result.drift);
        assertEquals(DriftCheck.REASON_HAN_ADDED, result.reason);
    }

    @Test
    public void chineseClauseTurnedIntoEnglishIsDrift() {
        String drifted = "I talked to Febe and she's like, 她说周末可能不来了, because her back is still hurting. "
                + "We can go visit her instead? Maybe Sunday afternoon.";
        DriftCheck.Result result = DriftCheck.check(MOM, drifted);
        assertTrue(result.drift);
        assertEquals(DriftCheck.REASON_LATIN_ADDED, result.reason);
    }

    @Test
    public void oneWordSwappedAcrossScriptsIsDrift() {
        String spoken = "那个，我觉得 we should 先 finish it, 因为 Milo 需要 it by Friday.";
        String swapped = "我觉得 we should 先 finish it, because Milo 需要 it by Friday.";
        DriftCheck.Result result = DriftCheck.check(spoken, swapped);
        assertTrue(result.drift);
        assertEquals(DriftCheck.REASON_SWAPPED, result.reason);
    }

    @Test
    public void englishSpeechTranslatedIntoChineseIsDrift() {
        DriftCheck.Result result = DriftCheck.check(
                "Can you ask Milo to add milk and eggs to the list?",
                "你能让 Milo 把牛奶和鸡蛋加到清单上吗？");
        assertTrue(result.drift);
    }

    @Test
    public void wholesaleTranslationIsDrift() {
        DriftCheck.Result result = DriftCheck.check(MEETING, "I'm going to a QBR meeting this afternoon and may be a bit late.");
        assertTrue(result.drift);
        assertEquals(DriftCheck.REASON_TRANSLATED, result.reason);
    }

    @Test
    public void emptyInputsAreSafe() {
        assertFalse(DriftCheck.check(null, null).drift);
        assertFalse(DriftCheck.check("", "").drift);
    }
}
