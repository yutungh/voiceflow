package com.voiceflowkeyboard.ime;

import org.json.JSONObject;

import java.io.IOException;

/**
 * A provider answered with a non-2xx status. The message is the same text the
 * clients always threw, because the keyboard shows it to the user; the fields
 * carry the provider's own error type and code so callers can classify the
 * failure without reading the message.
 *
 * <p>Error body shapes, as captured in the test fixtures:
 * <ul>
 *   <li>Anthropic: {@code {"type":"error","error":{"type":..., "message":...}}}</li>
 *   <li>OpenAI: {@code {"error":{"message":..., "type":..., "param":..., "code":...}}}</li>
 *   <li>xAI: {@code {"code":..., "error":"<message>"}}</li>
 *   <li>Deepgram: {@code {"err_code":..., "err_msg":..., "request_id":...}}</li>
 * </ul>
 */
final class ProviderHttpException extends IOException {
    final String provider;
    final int status;
    /** Provider error type, e.g. Anthropic {@code not_found_error}; empty when absent. */
    final String errorType;
    /** Provider error code, e.g. OpenAI {@code model_not_found}; empty when absent. */
    final String errorCode;
    /** The provider's human-readable error message; empty when absent. */
    final String errorMessage;
    final String rawBody;

    ProviderHttpException(
            String provider,
            String message,
            int status,
            String errorType,
            String errorCode,
            String errorMessage,
            String rawBody
    ) {
        super(message);
        this.provider = provider == null ? "" : provider;
        this.status = status;
        this.errorType = errorType == null ? "" : errorType;
        this.errorCode = errorCode == null ? "" : errorCode;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        this.rawBody = rawBody == null ? "" : rawBody;
    }

    /** Builds the exception the clients throw: "{label} failed ({status}): {body}". */
    static ProviderHttpException fromResponse(String provider, String label, int status, String body) {
        String raw = body == null ? "" : body;
        String type = "";
        String code = "";
        String message = "";
        try {
            JSONObject json = new JSONObject(raw);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                type = string(error, "type");
                code = string(error, "code");
                message = string(error, "message");
            } else if (json.has("err_code") || json.has("err_msg")) {
                code = string(json, "err_code");
                message = string(json, "err_msg");
            } else {
                code = string(json, "code");
                message = string(json, "error");
            }
        } catch (Exception ignored) {
            // Not JSON (an HTML gateway page, an empty body): no structured fields.
        }
        return new ProviderHttpException(
                provider,
                label + " failed (" + status + "): " + raw,
                status,
                type,
                code,
                message,
                raw
        );
    }

    /** org.json turns a JSON null into the text "null"; treat it as absent. */
    private static String string(JSONObject json, String name) {
        if (json == null || !json.has(name) || json.isNull(name)) {
            return "";
        }
        Object value = json.opt(name);
        return value instanceof String || value instanceof Number ? String.valueOf(value).trim() : "";
    }
}
