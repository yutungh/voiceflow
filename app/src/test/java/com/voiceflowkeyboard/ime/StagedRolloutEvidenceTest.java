package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * The phone's half of the staged rollout. The gateway lets a second phone update
 * only after the first phone has run the version for 24 hours with a fresh, clean
 * status and at least one dictation on it. This phone supplies that evidence: per
 * version, persistent across restarts, errors never forgotten, never any text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class StagedRolloutEvidenceTest {
    private static final long T0 = 1_800_000_000_000L;
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        File state = new File(new File(context.getFilesDir(), "sync"), "update-state.json");
        if (state.exists()) {
            assertTrue(state.delete());
        }
    }

    @Test
    public void aNewVersionStartsWithNoDictationsAndNoErrors() {
        UpdateEvidence old = UpdateEvidence.fresh(44, T0);
        old.recordDictation();
        old.recordError(UpdateEvidence.ERROR_AUTH, "auth-401");
        UpdateEvidence next = UpdateEvidence.forInstalled(old, 45, T0 + 1000);
        assertEquals(45, next.versionCode);
        assertEquals(0, next.dictations);
        assertEquals(0, next.errorCount());
        assertEquals(T0 + 1000, next.since);
    }

    @Test
    public void countersAndErrorsSurviveARestart() {
        SyncStore.updateEvidence(context, 44, e -> {
            e.recordDictation();
            e.recordDictation();
            e.recordError(UpdateEvidence.ERROR_INSTALL, "install-blocked");
        });
        // A new process reads the file again.
        UpdateEvidence reread = SyncStore.evidence(context, 44, T0);
        assertEquals(2, reread.dictations);
        assertEquals(1, reread.installErrors);
        assertEquals("install-blocked", reread.lastError);
        // Errors only grow within a version: a later clean run does not clear them.
        SyncStore.updateEvidence(context, 44, e -> e.lastError = "");
        assertEquals(1, SyncStore.evidence(context, 44, T0).installErrors);
    }

    @Test
    public void aPendingUpdateSurvivesUntilItIsInstalled() {
        UpdateEvidence running = UpdateEvidence.fresh(44, T0);
        running.pendingVersionCode = 45;
        running.pendingState = "downloaded";
        // Still on 44 after a restart: the download is still waiting.
        assertEquals(45, UpdateEvidence.forInstalled(running, 44, T0).pendingVersionCode);
        // Now on 45: finished, and the new version's evidence starts clean.
        UpdateEvidence after = UpdateEvidence.forInstalled(running, 45, T0 + 10);
        assertEquals(0, after.pendingVersionCode);
        assertEquals("", after.pendingState);
    }

    @Test
    public void theStatusCarriesTheBlockTheGatewayReads() throws Exception {
        UpdateEvidence evidence = UpdateEvidence.fresh(45, T0);
        evidence.recordDictation();
        evidence.recordError(UpdateEvidence.ERROR_VALIDATION, "http-422");
        evidence.recordCleanup(true, true, DriftCheck.REASON_SWAPPED);
        JSONObject status = StatusReport.build(
                new Enrolment("ph0a1b2c3d", "milo", "https://sync.pinewood.example"),
                "0.8.1",
                "Android 17 (API 37)",
                T0 + 5000,
                evidence
        );
        assertEquals("voiceflow-status", status.getString("schema"));
        assertEquals(1, status.getInt("schemaVersion"));
        assertEquals("VoiceFlow", status.getString("app"));
        assertEquals("ph0a1b2c3d", status.getString("device"));
        assertEquals("milo", status.getString("user"));
        assertTrue(status.has("profile"));
        JSONObject update = status.getJSONObject("update");
        assertEquals(45, update.getInt("versionCode"));
        assertEquals(1, update.getInt("dictations"));
        JSONObject errors = update.getJSONObject("errors");
        assertEquals(0, errors.getInt("auth"));
        assertEquals(1, errors.getInt("validation"));
        assertEquals(0, errors.getInt("install"));
        JSONObject cleanup = status.getJSONObject("cleanup");
        assertEquals(1, cleanup.getInt("fallbacks"));
        assertEquals(1, cleanup.getJSONObject("fallbackReasons").getInt(DriftCheck.REASON_SWAPPED));
    }

    @Test
    public void theStatusNeverHoldsTheTokenOrDictatedText() throws Exception {
        UpdateEvidence evidence = UpdateEvidence.fresh(44, T0);
        evidence.recordDictation();
        String text = StatusReport.build(
                new Enrolment("ph0a1b2c3d", "milo", "https://sync.pinewood.example"),
                "0.8.0", "Android 17 (API 37)", T0, evidence).toString();
        assertFalse(text.contains("vfs1."));
        assertFalse(text.contains("Transcript"));
        assertFalse(text.contains("https://"));
    }

    @Test
    public void networkFailuresAreNotErrorsButRefusalsAre() {
        UpdateEvidence evidence = UpdateEvidence.fresh(44, T0);
        evidence.lastError = "offline";
        assertEquals(0, evidence.errorCount());
        evidence.recordError(GatewayClient.errorKind(401), "http-401");
        evidence.recordError(GatewayClient.errorKind(503), "http-503");
        assertEquals(1, evidence.errorCount());
        assertEquals(1, evidence.authErrors);
    }
}
