package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

/**
 * 0.9.4: a style whose word list is off sends no words to any speech model and no word notes
 * to the tidy-up; corrections that rewrite the text still apply. Every word is invented.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StyleWordsOffTest {
    private static final String STYLE = "list_mixed-example";
    private Context context;
    private WordLists.DictationWords words;
    private TidyWords.Block notes;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        notes = new TidyWords.Block("Words: Zylophant (heard as \"zylo font\")",
                Collections.singletonList(new TidyWords.Word("Zylophant", Collections.singletonList("zylo font"))));
        words = new WordLists.DictationWords(false, "Vocabulary: Zylophant, Quorbel.",
                Arrays.asList("Zylophant", "Quorbel"),
                Collections.singletonList(new PhraseReplacement("zylo font", "Zylophant")), "snap-1", notes,
                Arrays.asList("Zylophant", "Quorbel", "Brindlewick"));
    }

    @Test
    public void onByDefaultTheWordsAreUntouched() {
        assertSame(words, words.forStyle(context, STYLE));
        assertSame(notes, TransformClient.cleanupWords(context, STYLE, notes));
    }

    @Test
    public void offSendsNoWordsToAnySpeechModelAndKeepsTheCorrections() throws Exception {
        Prefs.setStyleWordsOn(context, STYLE, false);
        WordLists.DictationWords off = words.forStyle(context, STYLE);
        // OpenAI: the vocabulary line (prompt) and keywords[] are what it sends.
        assertEquals("", off.prompt);
        assertTrue(off.keywords.isEmpty());
        // Soniox: context.terms.
        assertTrue(SonioxClient.termsFor(off).isEmpty());
        JSONObject request = SonioxClient.transcriptionRequest("file-1", "", DictationLanguage.CHINESE,
                SonioxClient.termsFor(off));
        assertFalse(request.toString(), request.has("context"));
        // Deepgram and xAI are never sent words; the tidy-up gets no word notes.
        assertTrue(off.tidyWords.words.isEmpty());
        assertEquals("", off.tidyWords.text);
        // Spellings that rewrite the text still apply.
        assertEquals(1, off.corrections.size());
        assertEquals("Zylophant", off.corrections.get(0).to);
        assertEquals("snap-1", off.snapshotId);
    }

    @Test
    public void offGivesTheTidyUpNoWordNotes() {
        Prefs.setStyleWordsOn(context, STYLE, false);
        assertSame(TidyWords.Block.EMPTY, TransformClient.cleanupWords(context, STYLE, notes));
        assertSame("other styles keep theirs", notes, TransformClient.cleanupWords(context, Prefs.PRESET_CLEAN, notes));
    }

    @Test
    public void thePhonesOwnListIsOffTooForThatStyle() {
        Prefs.setStyleWordsOn(context, STYLE, false);
        WordLists.DictationWords legacy = WordLists.DictationWords.legacy(
                Collections.singletonList(new PhraseReplacement("quor bell", "Quorbel")));
        assertFalse(legacy.keywords.isEmpty());
        WordLists.DictationWords off = legacy.forStyle(context, STYLE);
        assertTrue(off.keywords.isEmpty());
        assertEquals("", off.prompt);
        assertTrue(off.legacy);
        assertEquals(1, off.corrections.size());
    }
}
