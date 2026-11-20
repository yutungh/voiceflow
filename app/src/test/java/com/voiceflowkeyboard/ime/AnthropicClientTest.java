package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Request options per Claude model family. Sending an option a model rejects
 * fails every request with a 400, so each family must get exactly what it takes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AnthropicClientTest {

    private static JSONObject optionsFor(String model, int maxTokens) throws Exception {
        JSONObject body = new JSONObject();
        AnthropicClient.applyModelOptions(body, model, maxTokens);
        return body;
    }

    @Test
    public void haikuKeepsItsDeterministicRequest() throws Exception {
        JSONObject body = optionsFor("claude-haiku-4-5", 2048);
        assertEquals(0, body.getInt("temperature"));
        assertEquals(2048, body.getInt("max_tokens"));
        assertFalse(body.has("thinking"));
        assertFalse(body.has("output_config"));
    }

    @Test
    public void modelsThatRejectSamplingNeverGetTemperature() throws Exception {
        String[] models = {
                "claude-sonnet-5", "claude-opus-4-7", "claude-opus-4-8",
                "claude-opus-5", "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1",
        };
        for (String model : models) {
            assertFalse(model, optionsFor(model, 2048).has("temperature"));
        }
    }

    @Test
    public void unrecognisedModelsGetNoOptionalParameters() throws Exception {
        JSONObject body = optionsFor("claude-something-new", 2048);
        assertEquals(2048, body.getInt("max_tokens"));
        assertFalse(body.has("temperature"));
        assertFalse(body.has("thinking"));
        assertFalse(body.has("output_config"));
    }

    @Test
    public void sonnetFiveHasThinkingSwitchedOff() throws Exception {
        JSONObject body = optionsFor("claude-sonnet-5", 2048);
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
        assertEquals(2048, body.getInt("max_tokens"));
    }

    @Test
    public void opusFourEightNeedsNothingExtra() throws Exception {
        // It does not think unless asked, so omitting thinking is already fast.
        JSONObject body = optionsFor("claude-opus-4-8", 2048);
        assertFalse(body.has("thinking"));
        assertFalse(body.has("output_config"));
    }

    @Test
    public void alwaysThinkingModelsGetLowEffortAndRoomToThink() throws Exception {
        for (String model : new String[]{"claude-opus-5", "claude-opus-5-5", "claude-fable-5", "claude-fable-5-1"}) {
            JSONObject body = optionsFor(model, 2048);
            assertEquals(model, "low", body.getJSONObject("output_config").getString("effort"));
            assertTrue(model, body.getInt("max_tokens") >= 16000);
            assertFalse(model, body.has("thinking"));
        }
        assertEquals(20000, optionsFor("claude-fable-5", 20000).getInt("max_tokens"));
    }

    @Test
    public void modelIdsAreMatchedCaseInsensitively() throws Exception {
        assertFalse(optionsFor("Claude-Sonnet-5", 2048).has("temperature"));
        assertTrue(optionsFor(" CLAUDE-HAIKU-4-5 ", 2048).has("temperature"));
    }
}
