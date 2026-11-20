package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 0.8.5: the transcription request carries the first {@link WordPrompt#TRANSCRIBE_TERMS}
 * words chosen (this phone's words, the app's words, then priority); the tidy-up gets
 * every word chosen. The shared cases are in {@code pack/vectors/wordlists.json}
 * ({@code split}, run by {@link WordListVectorsTest}); these cover this phone's own
 * words, which VoiceFlow Desktop doesn't have, and the cap.
 */
public class WordPromptSplitTest {

    private static WordLayer.Term term(String text, int priority, String app, String layer) {
        return new WordLayer.Term(text.toLowerCase(), text, new ArrayList<>(), "", "", priority,
                Collections.singletonList(app), layer);
    }

    private static List<WordLayer.Term> every(int count) {
        List<WordLayer.Term> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(term(String.format("Every%02d", i), 90 - i, WordLayer.EVERY_APP, WordLayer.PERSONAL));
        }
        return out;
    }

    @Test
    public void thisPhonesWordsThenTheAppsWordsAreAmongTheTwentyAndLastInTheLine() {
        List<WordLayer.Term> phone = Collections.singletonList(
                term("Phoneword", 1, WordLayer.EVERY_APP, WordLayer.PHONE));
        List<WordLayer.Term> terms = new ArrayList<>(every(30));
        terms.add(term("Notewell", 5, "com.example.notes", WordLayer.PERSONAL));
        terms.addAll(phone);
        WordPrompt.Built built = WordPrompt.build(phone, terms, 1200, 80, "com.example.notes");

        assertEquals(32, built.chosen.size());
        assertEquals("Phoneword", built.chosen.get(0).text);
        assertEquals("Notewell", built.chosen.get(1).text);
        assertEquals(WordPrompt.TRANSCRIBE_TERMS, built.sent.size());
        List<String> expected = new ArrayList<>();
        for (int i = 17; i >= 0; i--) {
            expected.add(String.format("Every%02d", i));
        }
        expected.add("Notewell");
        expected.add("Phoneword");
        assertEquals(expected, built.sent);
        assertEquals(WordPrompt.PREFIX + String.join(", ", expected), built.prompt);
        assertEquals(expected, WordPrompt.keywords(built.sent));
    }

    @Test
    public void theRequestIsNeverLongerThanPromptTermsAndAnOverrideStaysWithinOneToForty() {
        List<WordLayer.Term> terms = every(60);
        assertEquals(12, WordPrompt.build(terms, 1200, 12, null).sent.size());
        assertEquals(20, WordPrompt.build(terms, 1200, 80, null).sent.size());
        assertEquals(30, WordPrompt.build(new ArrayList<>(), terms, 1200, 80, null, 30).sent.size());
        assertEquals(WordPrompt.TRANSCRIBE_TERMS_MAX,
                WordPrompt.build(new ArrayList<>(), terms, 1200, 80, null, 99).sent.size());
        assertEquals(1, WordPrompt.build(new ArrayList<>(), terms, 1200, 80, null, 0).sent.size());
        assertTrue(WordPrompt.build(terms, 1200, 80, null).prompt.length() <= 1200);
    }
}
