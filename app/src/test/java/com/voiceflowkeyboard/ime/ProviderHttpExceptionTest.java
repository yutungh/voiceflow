package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Real provider error bodies, captured by sending each provider one request
 * with a fake model ID (see app/src/test/resources/model-availability), run
 * through the same parsing and classification the clients use.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ProviderHttpExceptionTest {
    private static final String FAKE = "voiceflow-nonexistent-model-probe";

    private static JSONObject fixture(String name) throws Exception {
        try (InputStream in = ProviderHttpExceptionTest.class.getClassLoader()
                .getResourceAsStream("model-availability/" + name + ".json")) {
            assertNotNull("missing fixture " + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    /** The clients read bodies line by line and join them without separators. */
    private static String asClientReadsIt(String body) {
        return body.replace("\r", "").replace("\n", "");
    }

    private static ProviderHttpException fromFixture(String name, String label) throws Exception {
        JSONObject fixture = fixture(name);
        return ProviderHttpException.fromResponse(
                fixture.getString("provider"),
                label,
                fixture.getInt("status"),
                asClientReadsIt(fixture.getString("body"))
        );
    }

    @Test
    public void messageTextIsExactlyWhatTheClientsAlwaysThrew() {
        String body = "{\"error\":{\"message\":\"slow down\",\"type\":\"requests\",\"code\":\"rate_limit_exceeded\"}}";
        ProviderHttpException e = ProviderHttpException.fromResponse(Prefs.PROVIDER_OPENAI, "OpenAI request", 429, body);
        assertEquals("OpenAI request failed (429): " + body, e.getMessage());
        assertEquals(429, e.status);
        assertEquals("requests", e.errorType);
        assertEquals("rate_limit_exceeded", e.errorCode);
        assertEquals("slow down", e.errorMessage);
        assertEquals(body, e.rawBody);
    }

    @Test
    public void anthropicMessagesNotFoundFixtureIsMissing() throws Exception {
        ProviderHttpException e = fromFixture("anthropic_messages_model_missing", "Claude transform");
        assertEquals(404, e.status);
        assertEquals("not_found_error", e.errorType);
        assertEquals("model: " + FAKE, e.errorMessage);
        assertTrue(ModelAvailabilityPolicy.isModelMissing(e, FAKE));
        assertTrue(e.getMessage().startsWith("Claude transform failed (404): {"));
    }

    @Test
    public void anthropicLookupNotFoundFixtureIsMissing() throws Exception {
        ProviderHttpException e = fromFixture("anthropic_get_model_missing", "Anthropic model lookup");
        assertEquals(ModelAvailabilityPolicy.Verdict.MISSING, ModelAvailabilityPolicy.lookupVerdict(
                e.provider, e.status, e.errorType, e.errorCode, e.errorMessage, FAKE));
    }

    @Test
    public void anthropicAliasLookupAnswersWithTheDatedIdAndCountsAsExisting() throws Exception {
        JSONObject fixture = fixture("anthropic_get_model_alias");
        assertEquals(200, fixture.getInt("status"));
        String answeredId = new JSONObject(fixture.getString("body")).getString("id");
        // Why list membership is not used: the alias is not the ID the provider lists.
        assertNotEquals("claude-haiku-4-5", answeredId);
        assertTrue(answeredId.startsWith("claude-haiku-4-5-"));
        assertEquals(ModelAvailabilityPolicy.Verdict.EXISTS, ModelAvailabilityPolicy.lookupVerdict(
                Prefs.PROVIDER_ANTHROPIC, fixture.getInt("status"), "", "", "", "claude-haiku-4-5"));
    }

    @Test
    public void openAiNotFoundFixturesAreMissingOnEveryEndpoint() throws Exception {
        for (String name : new String[]{
                "openai_get_model_missing",
                "openai_responses_model_missing",
                "openai_transcriptions_model_missing"
        }) {
            ProviderHttpException e = fromFixture(name, "OpenAI request");
            assertEquals(name, 404, e.status);
            assertEquals(name, "model_not_found", e.errorCode);
            assertEquals(name, "invalid_request_error", e.errorType);
            assertTrue(name, ModelAvailabilityPolicy.isModelMissing(e, FAKE));
        }
    }

    @Test
    public void xAiNotFoundFixtureIsMissingOnlyForTheModelItNames() throws Exception {
        ProviderHttpException e = fromFixture("xai_chat_model_missing", "xAI transform");
        assertEquals(404, e.status);
        assertEquals("not-found", e.errorCode);
        assertTrue(e.errorMessage.startsWith("The model " + FAKE + " does not exist"));
        assertTrue(ModelAvailabilityPolicy.isModelMissing(e, FAKE));
        assertFalse(ModelAvailabilityPolicy.isModelMissing(e, "grok-4.3"));
    }

    @Test
    public void deepgramDocumentedUnknownModelIsNotMissing() throws Exception {
        ProviderHttpException e = fromFixture("deepgram_listen_unknown_model_documented", "Deepgram transcription");
        assertEquals(400, e.status);
        assertEquals("Bad Request", e.errorCode);
        assertTrue(e.errorMessage.contains("No such model/language/tier combination"));
        assertFalse(ModelAvailabilityPolicy.isModelMissing(e, "nova-9"));
    }

    @Test
    public void fourHundredBodiesAreNeverMissing() {
        String[][] cases = {
                {Prefs.PROVIDER_ANTHROPIC, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"temperature: This model does not support the temperature parameter.\"}}"},
                {Prefs.PROVIDER_ANTHROPIC, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"claude-fable-5 is not available for organizations with zero data retention.\"}}"},
                {Prefs.PROVIDER_ANTHROPIC, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\","
                        + "\"message\":\"model: claude-sonnet-5\"}}"},
                {Prefs.PROVIDER_OPENAI, "{\"error\":{\"message\":\"Unsupported parameter: 'temperature'\","
                        + "\"type\":\"invalid_request_error\",\"param\":\"temperature\",\"code\":\"unsupported_parameter\"}}"},
                {Prefs.PROVIDER_OPENAI, "{\"error\":{\"message\":\"x\",\"type\":\"invalid_request_error\","
                        + "\"param\":\"model\",\"code\":\"model_not_found\"}}"},
        };
        for (String[] c : cases) {
            ProviderHttpException e = ProviderHttpException.fromResponse(c[0], "request", 400, c[1]);
            assertFalse(c[1], ModelAvailabilityPolicy.isModelMissing(e, "claude-sonnet-5"));
        }
    }

    @Test
    public void authRateLimitServerAndGeneric404BodiesAreNotMissing() {
        Object[][] cases = {
                {Prefs.PROVIDER_OPENAI, 401, "{\"error\":{\"message\":\"Incorrect API key provided\","
                        + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_api_key\"}}"},
                {Prefs.PROVIDER_ANTHROPIC, 403, "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\","
                        + "\"message\":\"Your API key does not have permission to use the specified resource.\"}}"},
                {Prefs.PROVIDER_ANTHROPIC, 429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                        + "\"message\":\"Number of request tokens has exceeded your per-minute rate limit\"}}"},
                {Prefs.PROVIDER_ANTHROPIC, 529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\","
                        + "\"message\":\"Overloaded\"}}"},
                {Prefs.PROVIDER_OPENAI, 404, "{\"error\":{\"message\":\"Invalid URL (POST /v1/respones)\","
                        + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":null}}"},
                {Prefs.PROVIDER_ANTHROPIC, 404, "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\","
                        + "\"message\":\"Not Found\"}}"},
                {Prefs.PROVIDER_OPENAI, 502, "<html><body>Bad gateway</body></html>"},
                {Prefs.PROVIDER_XAI, 404, ""},
        };
        for (Object[] c : cases) {
            ProviderHttpException e = ProviderHttpException.fromResponse((String) c[0], "request", (Integer) c[1], (String) c[2]);
            assertFalse(String.valueOf(c[2]), ModelAvailabilityPolicy.isModelMissing(e, "claude-sonnet-5"));
            assertEquals("request failed (" + c[1] + "): " + c[2], e.getMessage());
        }
    }

    @Test
    public void jsonNullCodeIsTreatedAsAbsent() {
        ProviderHttpException e = ProviderHttpException.fromResponse(Prefs.PROVIDER_OPENAI, "OpenAI request", 404,
                "{\"error\":{\"message\":\"m\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":null}}");
        assertEquals("", e.errorCode);
    }
}
