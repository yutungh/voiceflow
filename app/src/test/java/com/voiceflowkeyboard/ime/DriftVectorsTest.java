package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared language-guard vectors in {@code pack/vectors/drift.json}. VoiceFlow
 * Desktop 0.9.13 runs the same file (drift-check.test.ts), so both apps hold back
 * the same tidy-ups.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DriftVectorsTest {
    /** 0.8.4: a case's tidy-up word list ({@code words}); absent means none. */
    private static List<TidyWords.Word> words(JSONObject c) throws Exception {
        List<TidyWords.Word> words = new ArrayList<>();
        JSONArray array = c.optJSONArray("words");
        if (array == null) {
            return words;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject w = array.getJSONObject(i);
            List<String> heard = new ArrayList<>();
            JSONArray forms = w.getJSONArray("heardAs");
            for (int j = 0; j < forms.length(); j++) {
                heard.add(forms.getString(j));
            }
            words.add(new TidyWords.Word(w.getString("text"), heard));
        }
        return words;
    }

    private static JSONObject vectors() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get("..", "pack", "vectors", "drift.json"));
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void everyCheckVector() throws Exception {
        JSONArray cases = vectors().getJSONArray("check");
        assertTrue(cases.length() >= 12);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            DriftCheck.Result result = DriftCheck.check(c.getString("source"), c.getString("output"), words(c));
            assertEquals(name + ": drift", c.getBoolean("drift"), result.drift);
            assertEquals(name + ": reason", c.getString("reason"), result.reason);
            assertEquals(name + ": mixed", c.getBoolean("mixed"), result.mixed);
        }
    }

    /**
     * Readers before writers (pack/README.md, Versions): the built-in styles file may
     * gain the version 2 style fields; this app's parser reads past fields it does not use.
     */
    @Test
    public void theBuiltinParserReadsPastNewStyleFields() throws Exception {
        String json = "{\"schema\":\"voiceflow-builtin-styles\",\"version\":1,\"styles\":[{\"id\":\"clean\",\"name\":\"Clean\","
                + "\"prompt\":\"p\",\"models\":{\"openai\":\"m\"},\"expressionOverride\":\"o\",\"hidden\":false,\"futureField\":1}]}";
        List<BuiltinStyles.Style> styles = BuiltinStyles.parse(json);
        assertEquals(1, styles.size());
        assertEquals("Clean", styles.get(0).name);
    }

    @Test
    public void everyGuardVector() throws Exception {
        JSONArray cases = vectors().getJSONArray("guard");
        assertTrue(cases.length() >= 5);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            JSONArray answers = c.getJSONArray("answers");
            List<String> queue = new ArrayList<>();
            for (int j = 0; j < answers.length(); j++) {
                queue.add(answers.getString(j));
            }
            List<Boolean> calls = new ArrayList<>();
            TransformClient.Outcome outcome = TransformClient.checkedTransform(
                    c.getString("transcript"),
                    c.getBoolean("fun"),
                    words(c),
                    strict -> {
                        calls.add(strict);
                        return queue.remove(0);
                    });
            JSONObject expect = c.getJSONObject("expect");
            assertEquals(name + ": text", expect.getString("text"), outcome.text);
            assertEquals(name + ": held back", expect.getString("heldBack"), outcome.heldBack);
            assertEquals(name + ": reason", expect.getString("reason"), outcome.fallbackReason);
            JSONArray want = expect.getJSONArray("calls");
            assertEquals(name + ": calls", want.length(), calls.size());
            for (int j = 0; j < want.length(); j++) {
                assertEquals(name + ": call " + j, want.getBoolean(j), calls.get(j));
            }
        }
    }
}
