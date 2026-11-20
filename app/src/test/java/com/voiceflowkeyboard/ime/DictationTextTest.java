package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DictationTextTest {
    private static final String MIXED = "Hey, Milo, remind me to send the draft to the team tomorrow morning. 然后晚上我们去吃火锅，记得提前订位子。";

    // --- translatedAway: the Work style turning Chinese into English ---

    @Test
    public void workStyleTranslationOfChineseIsCaught() {
        String translated = "Hey, Milo, remind me to send the draft to the team tomorrow morning. Then, let's go eat hot pot tonight, and remember to make a reservation ahead of time.";
        assertTrue(DictationText.translatedAway(MIXED, translated));
    }

    @Test
    public void midSentenceSwitchTranslatedToEnglishIsCaught() {
        String source = "I think we should 先把这个问题解决了 before we push the update，不然 Milo 会一直报错。";
        String translated = "I think we should solve this issue first before we push the update, otherwise the demo will keep throwing errors.";
        assertTrue(DictationText.translatedAway(source, translated));
    }

    @Test
    public void cleanupThatKeepsBothLanguagesPasses() {
        String cleaned = "Hey Milo, remind me to send the draft to the team tomorrow morning. 然后晚上我们去吃火锅，记得提前订位子。";
        assertFalse(DictationText.translatedAway(MIXED, cleaned));
    }

    @Test
    public void englishTranslatedIntoChineseIsCaught() {
        String source = "Can you ask Sam to add milk and eggs to the grocery list?";
        assertTrue(DictationText.translatedAway(source, "你能让 Sam 把牛奶和鸡蛋加到购物清单上吗？"));
    }

    @Test
    public void shortSourcesAreNeverFlagged() {
        assertFalse(DictationText.translatedAway("好的", "OK"));
        assertFalse(DictationText.translatedAway("sounds good", "好的"));
    }

    @Test
    public void removingChineseFillersIsNotTranslation() {
        assertFalse(DictationText.translatedAway("呃，那个，我们明天开会吧", "我们明天开会吧。"));
    }

    @Test
    public void retryThatStillTranslatesFallsBackToTheTranscript() {
        String stillEnglish = "Hey Milo, remind me to send the draft tomorrow. Let's have hot pot tonight and book a table.";
        assertEquals(MIXED, TransformClient.keepSpokenLanguage(MIXED, stillEnglish));
        String kept = "Hey Milo, remind me to send the draft to the team tomorrow morning. 然后晚上我们去吃火锅，记得提前订位子。";
        assertEquals(kept, TransformClient.keepSpokenLanguage(MIXED, kept));
    }

    // --- transcriptInput: the rule restated beside the transcript ---

    @Test
    public void englishOnlyTranscriptKeepsThePlainFraming() {
        assertEquals("Transcript:\nhello there", DictationText.transcriptInput("hello there", true, false));
    }

    @Test
    public void chineseTranscriptCarriesTheKeepLanguageNote() {
        String input = DictationText.transcriptInput(MIXED, true, false);
        assertTrue(input.startsWith("Transcript (it contains Chinese"));
        assertTrue(input.endsWith(MIXED));
        assertFalse(input.contains(DictationText.STRICT_LANGUAGE_NOTE));
    }

    @Test
    public void strictRetryAddsTheLanguageCheck() {
        String input = DictationText.transcriptInput(MIXED, true, true);
        assertTrue(input.startsWith(DictationText.STRICT_LANGUAGE_NOTE));
        assertTrue(input.endsWith(MIXED));
    }

    @Test
    public void funStylesKeepThePlainFraming() {
        assertEquals("Transcript:\n" + MIXED, DictationText.transcriptInput(MIXED, false, true));
    }

    // --- normalizeMixedScript: spacing and punctuation where the scripts meet ---

    @Test
    public void spaceIsAddedBetweenEnglishAndChinese() {
        assertEquals("I think we should 先把这个问题解决了 before we push",
                DictationText.normalizeMixedScript("I think we should先把这个问题解决了before we push"));
        assertEquals("不然 Milo 会一直报错。", DictationText.normalizeMixedScript("不然Milo会一直报错。"));
    }

    @Test
    public void fullWidthCommaEndingAnEnglishClauseBecomesAscii() {
        assertEquals("before we push the Pinewood update, 不然 Milo 会一直报错。",
                DictationText.normalizeMixedScript("before we push the Pinewood update，不然Milo会一直报错。"));
        assertEquals("Tell Sam that Wang Ayi is coming over on Saturday, and we're cooking.",
                DictationText.normalizeMixedScript("Tell Sam that Wang Ayi is coming over on Saturday，and we're cooking。"));
    }

    @Test
    public void longEnglishRunAfterChineseCountsAsAnEnglishClause() {
        assertEquals("I think we should 先把这个问题解决了 before we push the update, 不然会报错。",
                DictationText.normalizeMixedScript("I think we should 先把这个问题解决了 before we push the update，不然会报错。"));
    }

    @Test
    public void shortEnglishNameInsideChineseKeepsFullWidthPunctuation() {
        assertEquals("我用了 iPhone，很好用。", DictationText.normalizeMixedScript("我用了iPhone，很好用。"));
        assertEquals("我觉得 Claude Code，还不错。", DictationText.normalizeMixedScript("我觉得Claude Code，还不错。"));
    }

    @Test
    public void chinesePunctuationAfterChineseIsUntouched() {
        String text = "明天早上九点提醒我给妈妈打电话，然后下午去超市买鸡蛋、牛奶和面包，晚上七点前回家。";
        assertEquals(text, DictationText.normalizeMixedScript(text));
        assertEquals("我今天下午要去开会。Also, can you ask Sam?",
                DictationText.normalizeMixedScript("我今天下午要去开会。Also, can you ask Sam?"));
    }

    @Test
    public void digitsBesideChineseAreLeftAlone() {
        assertEquals("买了3个，晚上7点见。", DictationText.normalizeMixedScript("买了3个，晚上7点见。"));
    }

    @Test
    public void englishOnlyTextIsUnchanged() {
        String text = "Can you send me the latest QBR for the program review?";
        assertEquals(text, DictationText.normalizeMixedScript(text));
        assertEquals("", DictationText.normalizeMixedScript(null));
    }

    @Test
    public void fullWidthMarkBeforeAClosingQuoteGetsNoExtraSpace() {
        assertEquals("He said \"ok, fine.\"", DictationText.normalizeMixedScript("He said \"ok, fine。\""));
    }
}
