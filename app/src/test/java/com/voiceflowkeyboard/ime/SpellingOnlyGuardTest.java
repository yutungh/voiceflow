package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class SpellingOnlyGuardTest {

    @Test
    public void acceptsTypoCorrectionsWithoutTouchingStructure() {
        String source = "Teh quick fox!!\nSecond recieve line?";
        String corrected = "The quick fox!!\nSecond receive line?";

        assertEquals(corrected, SpellingOnlyGuard.requireSafe(source, corrected));
    }

    @Test
    public void acceptsReportedSentenceIncludingContraction() {
        String source = "Teh applw doesnf fall far frrom the tree";
        String corrected = "The apple doesn't fall far from the tree";

        assertEquals(corrected, SpellingOnlyGuard.requireSafe(source, corrected));
    }

    @Test
    public void acceptsInternalApostropheAndHyphenButPreservesSentencePunctuation() {
        assertEquals("I can't spell well-being!!",
                SpellingOnlyGuard.requireSafe("I cant spell wellbeing!!",
                        "I can't spell well-being!!"));
        assertEquals("I can\u2019t spell.",
                SpellingOnlyGuard.requireSafe("I cant spell.", "I can\u2019t spell."));
        assertRejected("I cant spell!!", "I can't spell!");
    }

    @Test
    public void restoresOuterWhitespaceTrimmedByTransformProvider() {
        assertEquals("\n  the word  \n",
                SpellingOnlyGuard.requireSafe("\n  teh word  \n", "the word"));
    }

    @Test
    public void rejectsChangedPunctuationOrWhitespace() {
        assertRejected("Fix teh!!\nNow", "Fix the!\nNow");
        assertRejected("Fix teh!!\nNow", "Fix the!! Now");
    }

    @Test
    public void rejectsRewordingAndAddedOrRemovedWords() {
        assertRejected("A big hous", "A large house");
        assertRejected("I typed teh word", "I typed the correct word");
        assertRejected("I typed teh word", "I typed word");
    }

    @Test
    public void rejectsCapitalizationAndNonLatinChanges() {
        assertRejected("teh WORD 中文", "The WORD 中文");
        assertRejected("teh WORD 中文", "the WORD 中國");
    }

    private static void assertRejected(String source, String candidate) {
        try {
            SpellingOnlyGuard.requireSafe(source, candidate);
            fail("Expected unsafe spelling result to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
