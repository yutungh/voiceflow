package com.voiceflowkeyboard.ime;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.TreeMap;

/**
 * What this phone reports about the version it runs, kept across restarts.
 *
 * <p>Counters belong to one installed versionCode: a different versionCode
 * starts them at zero. Within a version they only ever grow, so an error is
 * never forgotten by a restart. The gateway's canary rule reads
 * {@code dictations} and {@code errors} from the status file's {@code update}
 * block; nothing here ever holds dictated text.
 */
final class UpdateEvidence {
    static final String ERROR_AUTH = "auth";
    static final String ERROR_VALIDATION = "validation";
    static final String ERROR_INSTALL = "install";

    int versionCode;
    long since;
    int dictations;
    int authErrors;
    int validationErrors;
    int installErrors;
    long lastCheckAt;
    /** A short code for the last problem ("auth-401", "install-signer", "offline"); never text from a server. */
    String lastError = "";
    /** The versionCode downloaded and verified, waiting for the install conditions; 0 when none. */
    int pendingVersionCode;
    /** "", "downloaded", "committed", "play-protect-hold" or "needs-user" (UpdatePolicy.STATE_*). */
    String pendingState = "";
    /** Installs that waited two minutes or more for Google Play Protect: a hold, never an error. */
    int playProtectHolds;
    /** Failed install attempts for {@link #pendingVersionCode}, and when the last one was. */
    int installAttempts;
    long lastInstallAttemptAt;
    /** The PackageInstaller session of the last commit, so a withdrawn release can be abandoned. */
    int pendingSessionId;
    /** Dictations whose transcript mixed Chinese and English, retries, and fallbacks by reason code. */
    int mixedDictations;
    int driftRetries;
    final TreeMap<String, Integer> fallbacks = new TreeMap<>();

    private UpdateEvidence() {
    }

    static UpdateEvidence fresh(int versionCode, long now) {
        UpdateEvidence evidence = new UpdateEvidence();
        evidence.versionCode = versionCode;
        evidence.since = now;
        return evidence;
    }

    /**
     * The stored evidence when it is for the installed version; otherwise fresh
     * counters for it. A pending update at or below the installed version is
     * finished and dropped.
     */
    static UpdateEvidence forInstalled(UpdateEvidence stored, int installedVersionCode, long now) {
        if (stored == null || stored.versionCode != installedVersionCode) {
            UpdateEvidence fresh = fresh(installedVersionCode, now);
            if (stored != null && stored.pendingVersionCode > installedVersionCode) {
                fresh.pendingVersionCode = stored.pendingVersionCode;
                fresh.pendingState = stored.pendingState;
                fresh.installAttempts = stored.installAttempts;
                fresh.lastInstallAttemptAt = stored.lastInstallAttemptAt;
                fresh.pendingSessionId = stored.pendingSessionId;
            }
            // A hold belongs to the attempt, not the version that made it.
            fresh.playProtectHolds = stored == null ? 0 : stored.playProtectHolds;
            fresh.lastCheckAt = stored == null ? 0 : stored.lastCheckAt;
            return fresh;
        }
        if (stored.pendingVersionCode != 0 && stored.pendingVersionCode <= installedVersionCode) {
            stored.clearPending();
        }
        return stored;
    }

    void recordError(String kind, String code) {
        if (ERROR_AUTH.equals(kind)) {
            authErrors++;
        } else if (ERROR_VALIDATION.equals(kind)) {
            validationErrors++;
        } else if (ERROR_INSTALL.equals(kind)) {
            installErrors++;
        }
        lastError = code == null ? "" : code;
    }

    int errorCount() {
        return authErrors + validationErrors + installErrors;
    }

    void recordDictation() {
        dictations++;
    }

    void recordCleanup(boolean mixed, boolean retried, String fallbackReason) {
        if (mixed) {
            mixedDictations++;
        }
        if (retried) {
            driftRetries++;
        }
        if (fallbackReason != null && !fallbackReason.isEmpty()) {
            fallbacks.merge(fallbackReason, 1, Integer::sum);
        }
    }

    int fallbackCount() {
        int total = 0;
        for (int n : fallbacks.values()) {
            total += n;
        }
        return total;
    }

    void clearPending() {
        pendingVersionCode = 0;
        pendingState = "";
        installAttempts = 0;
        lastInstallAttemptAt = 0;
        pendingSessionId = 0;
    }

    JSONObject toJson() throws JSONException {
        JSONObject errors = new JSONObject()
                .put(ERROR_AUTH, authErrors)
                .put(ERROR_VALIDATION, validationErrors)
                .put(ERROR_INSTALL, installErrors);
        JSONObject fallbackJson = new JSONObject();
        for (java.util.Map.Entry<String, Integer> entry : fallbacks.entrySet()) {
            fallbackJson.put(entry.getKey(), (int) entry.getValue());
        }
        return new JSONObject()
                .put("v", 1)
                .put("versionCode", versionCode)
                .put("since", since)
                .put("dictations", dictations)
                .put("errors", errors)
                .put("lastCheckAt", lastCheckAt)
                .put("lastError", lastError)
                .put("pendingVersionCode", pendingVersionCode)
                .put("pendingState", pendingState)
                .put("installAttempts", installAttempts)
                .put("lastInstallAttemptAt", lastInstallAttemptAt)
                .put("pendingSessionId", pendingSessionId)
                .put("playProtectHolds", playProtectHolds)
                .put("cleanup", new JSONObject()
                        .put("mixed", mixedDictations)
                        .put("retries", driftRetries)
                        .put("fallbacks", fallbackJson));
    }

    static UpdateEvidence fromJson(JSONObject json) {
        if (json == null || json.optInt("v", 0) != 1) {
            return null;
        }
        UpdateEvidence e = new UpdateEvidence();
        e.versionCode = json.optInt("versionCode", 0);
        e.since = json.optLong("since", 0);
        e.dictations = nonNegative(json.optInt("dictations", 0));
        JSONObject errors = json.optJSONObject("errors");
        if (errors != null) {
            e.authErrors = nonNegative(errors.optInt(ERROR_AUTH, 0));
            e.validationErrors = nonNegative(errors.optInt(ERROR_VALIDATION, 0));
            e.installErrors = nonNegative(errors.optInt(ERROR_INSTALL, 0));
        }
        e.lastCheckAt = json.optLong("lastCheckAt", 0);
        e.lastError = json.optString("lastError", "");
        e.pendingVersionCode = nonNegative(json.optInt("pendingVersionCode", 0));
        e.pendingState = json.optString("pendingState", "");
        e.installAttempts = nonNegative(json.optInt("installAttempts", 0));
        e.lastInstallAttemptAt = json.optLong("lastInstallAttemptAt", 0);
        e.pendingSessionId = nonNegative(json.optInt("pendingSessionId", 0));
        e.playProtectHolds = nonNegative(json.optInt("playProtectHolds", 0));
        JSONObject cleanup = json.optJSONObject("cleanup");
        if (cleanup != null) {
            e.mixedDictations = nonNegative(cleanup.optInt("mixed", 0));
            e.driftRetries = nonNegative(cleanup.optInt("retries", 0));
            JSONObject fallbackJson = cleanup.optJSONObject("fallbacks");
            if (fallbackJson != null) {
                Iterator<String> keys = fallbackJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    int n = nonNegative(fallbackJson.optInt(key, 0));
                    if (n > 0 && key.matches("[a-z-]{1,32}")) {
                        e.fallbacks.put(key, n);
                    }
                }
            }
        }
        return e.versionCode > 0 ? e : null;
    }

    private static int nonNegative(int n) {
        return Math.max(0, n);
    }
}
