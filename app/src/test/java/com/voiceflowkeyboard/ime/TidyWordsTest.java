package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 0.8.4: the tidy-up's word list. The shared cases in {@code pack/vectors/tidywords.json}
 * (VoiceFlow Desktop 0.9.14 runs the same file in tidy-words.test.ts), so both apps send
 * the same block from the same words; then how this app carries it into a dictation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TidyWordsTest {

    private static List<WordLayer.Term> terms(JSONArray array) throws Exception {
        List<WordLayer.Term> terms = new ArrayList<>();
        for (int j = 0; j < array.length(); j++) {
            JSONObject t = array.getJSONObject(j);
            terms.add(new WordLayer.Term(t.getString("id"), t.getString("text"),
                    t.has("heardAs") ? WordListVectorsTest.strings(t.getJSONArray("heardAs")) : new ArrayList<>(),
                    t.optString("kind", ""), t.optString("gloss", ""), t.optInt("priority", 50),
                    WordListVectorsTest.strings(t.getJSONArray("apps")), t.getString("layer")));
        }
        return terms;
    }

    @Test
    public void theHeaderAndTheRuleAreTheTextBothAppsSend() throws Exception {
        JSONObject vectors = WordListVectorsTest.vectors("tidywords.json");
        assertEquals(vectors.getString("header"), TidyWords.HEADER);
        assertEquals(vectors.getString("rule"), TidyWords.RULE);
    }

    @Test
    public void everyBlockCase() throws Exception {
        JSONObject vectors = WordListVectorsTest.vectors("tidywords.json");
        JSONArray cases = vectors.getJSONArray("block");
        assertTrue(cases.length() >= 6);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            String app = c.isNull("app") ? null : c.getString("app");
            WordPrompt.Built built = WordPrompt.build(terms(c.getJSONArray("terms")),
                    c.getInt("promptChars"), c.getInt("promptTerms"), app);
            TidyWords.Block block = TidyWords.forTerms(built.chosen, c.getInt("tidyChars"));
            JSONObject expect = c.getJSONObject("expect");
            List<String> lines = WordListVectorsTest.strings(expect.getJSONArray("lines"));
            String text = lines.isEmpty() ? ""
                    : vectors.getString("header") + "\n" + String.join("\n", lines) + "\n\n" + vectors.getString("rule");
            assertEquals(name + ": text", text, block.text);
            JSONArray words = expect.getJSONArray("words");
            assertEquals(name + ": words", words.length(), block.words.size());
            for (int j = 0; j < words.length(); j++) {
                assertEquals(name + ": word " + j, words.getJSONObject(j).getString("text"), block.words.get(j).text);
                assertEquals(name + ": heard " + j,
                        WordListVectorsTest.strings(words.getJSONObject(j).getJSONArray("heardAs")),
                        block.words.get(j).heardAs);
            }
        }
    }

    @Test
    public void aDictationFromTheNasCarriesTheBlockAndTheZeroPointEightOneOneDoesNot() {
        WordLayer.Term zdrl = new WordLayer.Term("a", "ZDRL", Collections.singletonList("Z-drill"), "acronym", "",
                90, Collections.singletonList(WordLayer.EVERY_APP), WordLayer.PERSONAL);
        WordPrompt.Built built = WordPrompt.build(Collections.singletonList(zdrl), 600, 40, null);
        assertEquals(Collections.singletonList(zdrl), built.chosen);
        TidyWords.Block block = TidyWords.forTerms(built.chosen, TidyWords.TIDY_WORDS_CHARS);
        assertTrue(block.text.contains("- ZDRL (acronym; speech-to-text wrote \"Z-drill\")"));

        WordLists.DictationWords legacy = WordLists.DictationWords.legacy(new ArrayList<>());
        assertSame(TidyWords.Block.EMPTY, legacy.tidyWords);
        assertEquals("", legacy.tidyWords.text);
    }

    @Test
    public void theBlockGoesAfterTheStylesOwnInstructions() {
        assertEquals("Tidy it up.\n\nBLOCK", TidyWords.withBlock("Tidy it up.\n  ", "BLOCK"));
        assertEquals("Tidy it up.\n", TidyWords.withBlock("Tidy it up.\n", ""));
        assertEquals("Tidy it up.", TidyWords.withBlock("Tidy it up.", null));
    }

    @Test
    public void theGuardCreditsAListedWordOnlyThroughTheTransformItWasGiven() throws Exception {
        String spoken = "嗯，让 phoebe 看一下 pine wood 的 plan 吧。";
        String fixed = "让 Febe 看一下 Pinewood 的 plan 吧。";
        List<TidyWords.Word> words = Arrays.asList(
                new TidyWords.Word("Febe", Collections.singletonList("Phoebe")),
                new TidyWords.Word("Pinewood", Collections.singletonList("Pine Wood")));
        List<Boolean> calls = new ArrayList<>();
        TransformClient.Outcome outcome = TransformClient.checkedTransform(spoken, false, words, strict -> {
            calls.add(strict);
            return fixed;
        });
        assertEquals(fixed, outcome.text);
        assertFalse(outcome.fellBack());
        assertEquals(Collections.singletonList(false), calls);

        // Without the word list, the same answer is drift: retried, then his words.
        calls.clear();
        TransformClient.Outcome plain = TransformClient.checkedTransform(spoken, false, strict -> {
            calls.add(strict);
            return fixed;
        });
        assertTrue(plain.fellBack());
        assertEquals(Arrays.asList(false, true), calls);
    }

    @Test
    public void aNoteNeverPassesItsLimit() {
        StringBuilder gloss = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            gloss.append('x');
        }
        WordLayer.Term term = new WordLayer.Term("a", "Flumox", Arrays.asList("Flu Mocks", "Flew Mox"), "",
                gloss.toString(), 90, Collections.singletonList(WordLayer.EVERY_APP), WordLayer.PERSONAL);
        assertTrue(TidyWords.note(term).length() <= TidyWords.TIDY_NOTE_CHARS);
    }
}
