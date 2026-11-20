package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The shared word-list vectors in {@code pack/} (the same files VoiceFlow
 * Desktop runs in {@code cargo test} and {@code bun test}): every merge case in
 * {@code merge.json} and {@code wordlists.json}, and every prompt case.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WordListVectorsTest {

    static JSONObject vectors(String name) throws Exception {
        for (String base : new String[]{"../pack/vectors", "pack/vectors"}) {
            File file = new File(base, name);
            if (file.isFile()) {
                return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("pack/vectors/" + name + " not found from " + new File(".").getAbsolutePath());
    }

    @Test
    public void mergeJsonCases() throws Exception {
        JSONArray cases = vectors("merge.json").getJSONArray("cases");
        assertTrue(cases.length() >= 10);
        runMergeCases(cases);
    }

    @Test
    public void wordlistsJsonMergeCases() throws Exception {
        JSONArray cases = vectors("wordlists.json").getJSONArray("merge");
        assertTrue(cases.length() >= 10);
        runMergeCases(cases);
    }

    private static void runMergeCases(JSONArray cases) throws Exception {
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            String user = c.optString("user", "sam");
            List<WordLayer> layers = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            for (String layer : WordLayer.FILE_LAYERS) {
                if (!c.has(layer)) {
                    continue;
                }
                try {
                    layers.add(WordLayer.parse(c.getJSONObject(layer).toString().getBytes(StandardCharsets.UTF_8), layer, user));
                } catch (WordLayer.Rejected e) {
                    rejected.add(layer);
                }
            }
            List<WordOp> ops = new ArrayList<>();
            JSONArray opArray = c.optJSONArray("ops");
            for (int j = 0; opArray != null && j < opArray.length(); j++) {
                WordOp op = WordOp.parse(opArray.getJSONObject(j));
                assertNotNull(name + ": edit " + j + " parses", op);
                ops.add(op);
            }
            WordMerge.Snapshot s = WordMerge.merge(layers, ops, Collections.emptySet());
            JSONObject expect = c.getJSONObject("expect");

            Map<String, String> want = new TreeMap<>();
            JSONObject wantTerms = expect.getJSONObject("terms");
            Iterator<String> keys = wantTerms.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                want.put(k, wantTerms.getString(k));
            }
            Map<String, String> got = new TreeMap<>();
            for (WordLayer.Term t : s.terms) {
                got.put(t.id, t.text);
            }
            assertEquals(name + ": words", want, got);

            List<String> corrections = new ArrayList<>();
            for (WordLayer.Correction cc : s.corrections) {
                corrections.add(String.join("/", cc.from) + " -> " + cc.to);
            }
            assertEquals(name + ": corrections", strings(expect.getJSONArray("corrections")), corrections);

            // Issue codes as a set: VoiceFlow Desktop reports one per occurrence.
            assertEquals(name + ": issues", new TreeSet<>(strings(expect.getJSONArray("issues"))), new TreeSet<>(s.issues));
            assertEquals(name + ": rejected", strings(expect.getJSONArray("rejected")), rejected);
        }
    }

    @Test
    public void promptCases() throws Exception {
        JSONArray cases = vectors("wordlists.json").getJSONArray("prompt");
        assertTrue(cases.length() >= 5);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            WordPrompt.Built built = buildCase(c);
            JSONObject expect = c.getJSONObject("expect");
            assertEquals(name + ": prompt", expect.getString("prompt"), built.prompt);
            assertEquals(name + ": sent", strings(expect.getJSONArray("sent")), built.sent);
            assertEquals(name + ": keywords", strings(expect.getJSONArray("keywords")), WordPrompt.keywords(built.sent));
        }
    }

    /** 0.8.5: the transcription request carries 20 words; the tidy-up gets every word chosen. */
    @Test
    public void splitCases() throws Exception {
        JSONArray cases = vectors("wordlists.json").getJSONArray("split");
        assertTrue(cases.length() >= 3);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            WordPrompt.Built built = buildCase(c);
            JSONObject expect = c.getJSONObject("expect");
            assertEquals(name + ": prompt", expect.getString("prompt"), built.prompt);
            assertEquals(name + ": sent", strings(expect.getJSONArray("sent")), built.sent);
            assertEquals(name + ": keywords", strings(expect.getJSONArray("keywords")), WordPrompt.keywords(built.sent));
            List<String> tidy = new ArrayList<>();
            for (TidyWords.Word w : TidyWords.forTerms(built.chosen, TidyWords.TIDY_WORDS_CHARS).words) {
                tidy.add(w.text);
            }
            assertEquals(name + ": tidyWords", strings(expect.getJSONArray("tidyWords")), tidy);
        }
    }

    private static WordPrompt.Built buildCase(JSONObject c) throws Exception {
        List<WordLayer.Term> terms = new ArrayList<>();
        JSONArray array = c.getJSONArray("terms");
        for (int j = 0; j < array.length(); j++) {
            JSONObject t = array.getJSONObject(j);
            terms.add(new WordLayer.Term(t.getString("id"), t.getString("text"),
                    t.has("heardAs") ? strings(t.getJSONArray("heardAs")) : new ArrayList<>(),
                    t.optString("kind", ""), t.optString("gloss", ""), t.optInt("priority", 50),
                    strings(t.getJSONArray("apps")), t.getString("layer")));
        }
        String app = c.isNull("app") ? null : c.getString("app");
        return WordPrompt.build(terms, c.getInt("promptChars"), c.getInt("promptTerms"), app);
    }

    static List<String> strings(JSONArray array) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return out;
    }
}
