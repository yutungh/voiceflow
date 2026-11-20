package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PersonalVocabularyTest {
    private final List<PhraseReplacement> replacements = Arrays.asList(
            new PhraseReplacement(
                    "sonny\nson nee\nsunnie",
                    "sunny",
                    "sunny is the user's nickname for his partner Sam."
            ),
            new PhraseReplacement(
                    "pine wud\npine wudd",
                    "Pinewood",
                    "Pinewood is the user's personal software project."
            ),
            new PhraseReplacement(
                    "n p m run pre flight",
                    "npm run preflight",
                    "npm run preflight is an exact lowercase command."
            )
    );

    @Test
    public void appliesNicknameAndProjectCorrections() {
        assertEquals(
                "Tell sunny that Pinewood is ready",
                PersonalVocabulary.applyReplacements(
                        "Tell sonny that Pine Wud is ready",
                        replacements
                )
        );
    }

    @Test
    public void normalizesAcronymSpacingDotsAndHyphenation() {
        assertEquals(
                "npm run preflight",
                PersonalVocabulary.applyReplacements("N.P.M. run pre-flight", replacements)
        );
        assertEquals(
                "Please npm run preflight now",
                PersonalVocabulary.applyReplacements("Please n p m run pre flight now", replacements)
        );
    }

    @Test
    public void doesNotReplaceInsideAnotherWord() {
        assertEquals(
                "ssonny",
                PersonalVocabulary.applyReplacements("ssonny", replacements)
        );
    }

    @Test
    public void buildsDeduplicatedKeywordsAndContextPrompt() {
        List<String> keywords = PersonalVocabulary.keywords(replacements);
        assertEquals(Arrays.asList("sunny", "Pinewood", "npm run preflight"), keywords);

        String prompt = PersonalVocabulary.transcriptionPrompt(replacements);
        assertTrue(prompt.contains("sunny"));
        assertTrue(prompt.contains("Sam"));
        assertTrue(prompt.contains("Pinewood"));
        assertTrue(prompt.contains("npm run preflight"));
        assertFalse(prompt.contains("sonny"));
    }

    @Test
    public void findsFlexibleReplacementAtTextEnd() {
        PersonalVocabulary.ReplacementMatch match = PersonalVocabulary.findReplacementAtEnd(
                "Please N P M run pre flight",
                replacements
        );
        assertTrue(match != null);
        assertEquals("npm run preflight", match.replacement);
        assertEquals("N P M run pre flight".length(), match.matchedLength);
    }
}
