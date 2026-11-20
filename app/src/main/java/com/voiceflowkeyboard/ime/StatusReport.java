package com.voiceflowkeyboard.ime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The status file this phone writes ({@code voiceflow-status} v1, the same
 * contract as the PC), plus an {@code update} block the gateway's canary rule
 * reads and a {@code cleanup} block with the drift-check counters. Counts and
 * short codes only: never dictated text, never words from the lists.
 */
final class StatusReport {
    private StatusReport() {
    }

    static JSONObject build(
            Enrolment enrolment,
            String versionName,
            String os,
            long now,
            UpdateEvidence evidence
    ) throws JSONException {
        return build(enrolment, versionName, os, now, evidence, null);
    }

    /** {@code wordLists}: the word lists block ({@link WordLists#statusBlock}); null for an empty one. */
    static JSONObject build(
            Enrolment enrolment,
            String versionName,
            String os,
            long now,
            UpdateEvidence evidence,
            JSONObject wordLists
    ) throws JSONException {
        JSONObject errors = new JSONObject()
                .put(UpdateEvidence.ERROR_AUTH, evidence.authErrors)
                .put(UpdateEvidence.ERROR_VALIDATION, evidence.validationErrors)
                .put(UpdateEvidence.ERROR_INSTALL, evidence.installErrors);
        JSONObject update = new JSONObject()
                .put("versionCode", evidence.versionCode)
                .put("since", iso(evidence.since))
                .put("dictations", evidence.dictations)
                .put("errors", errors)
                .put("lastCheckAt", evidence.lastCheckAt > 0 ? iso(evidence.lastCheckAt) : JSONObject.NULL)
                .put("pending", evidence.pendingVersionCode > 0 ? (Object) evidence.pendingVersionCode : JSONObject.NULL)
                .put("pendingState", evidence.pendingState)
                .put("lastError", evidence.lastError.isEmpty() ? JSONObject.NULL : evidence.lastError)
                // Not errors: installs that waited for Google Play Protect's dialog.
                .put("holds", new JSONObject().put("play-protect-hold", evidence.playProtectHolds));
        JSONObject fallbacks = new JSONObject();
        for (Map.Entry<String, Integer> entry : evidence.fallbacks.entrySet()) {
            fallbacks.put(entry.getKey(), (int) entry.getValue());
        }
        JSONObject cleanup = new JSONObject()
                .put("mixedDictations", evidence.mixedDictations)
                .put("retries", evidence.driftRetries)
                .put("fallbacks", evidence.fallbackCount())
                .put("fallbackReasons", fallbacks);
        JSONObject profile = wordLists != null ? wordLists : new JSONObject()
                .put("lastSyncAt", JSONObject.NULL)
                .put("sourceReachable", true)
                .put("usingCache", false)
                .put("pendingEdits", 0)
                .put("errors", new JSONArray());
        return new JSONObject()
                .put("schema", "voiceflow-status")
                .put("schemaVersion", 1)
                .put("app", "VoiceFlow")
                .put("version", versionName)
                .put("os", os)
                .put("device", enrolment.device)
                .put("user", enrolment.user)
                .put("writtenAt", iso(now))
                .put("profile", profile)
                .put("update", update)
                .put("cleanup", cleanup);
    }

    static String iso(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
