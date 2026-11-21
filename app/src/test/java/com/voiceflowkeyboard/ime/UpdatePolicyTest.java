package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The self-updater's rules: what it takes, when it downloads, when it installs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class UpdatePolicyTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SIGNER = "41391299c69adb88387544f70f765f89ea73bffae399a4a1a0bad1b79f6734a1";
    private static final String OTHER_SIGNER = "ffff1299c69adb88387544f70f765f89ea73bffae399a4a1a0bad1b79f6734a1";
    private static final String PKG = "com.voiceflowkeyboard.ime";

    private static UpdatePolicy.Offer offer(int versionCode) {
        return new UpdatePolicy.Offer(versionCode, "0.8.1", SHA, 1000);
    }

    // --- the offer ---

    @Test
    public void readsTheGatewaysOfferAndIgnoresItsUrl() throws Exception {
        UpdatePolicy.Offer parsed = UpdatePolicy.parseOffer(new JSONObject(
                "{\"offer\":{\"versionCode\":45,\"versionName\":\"0.8.1\",\"sha256\":\"" + SHA.toUpperCase()
                        + "\",\"size\":1000,\"url\":\"https://elsewhere.example/evil.apk\"}}"));
        assertEquals(45, parsed.versionCode);
        assertEquals(SHA, parsed.sha256);
        assertEquals(1000, parsed.size);
    }

    @Test
    public void noOfferOrAMalformedOneIsNothingToDo() throws Exception {
        assertNull(UpdatePolicy.parseOffer(new JSONObject("{\"offer\":null}")));
        assertNull(UpdatePolicy.parseOffer(new JSONObject("{}")));
        assertNull(UpdatePolicy.parseOffer(new JSONObject(
                "{\"offer\":{\"versionCode\":45,\"versionName\":\"0.8.1\",\"sha256\":\"abc\",\"size\":1000}}")));
        assertNull(UpdatePolicy.parseOffer(new JSONObject(
                "{\"offer\":{\"versionCode\":45,\"versionName\":\"0.8.1\",\"sha256\":\"" + SHA + "\",\"size\":0}}")));
        assertNull(UpdatePolicy.parseOffer(new JSONObject(
                "{\"offer\":{\"versionCode\":0,\"versionName\":\"0.8.1\",\"sha256\":\"" + SHA + "\",\"size\":10}}")));
        assertNull(UpdatePolicy.parseOffer(new JSONObject(
                "{\"offer\":{\"versionCode\":45,\"versionName\":\"0.8.1; rm\",\"sha256\":\"" + SHA + "\",\"size\":10}}")));
        assertNull(UpdatePolicy.parseOffer(new JSONObject(
                "{\"offer\":{\"versionCode\":45,\"versionName\":\"0.8.1\",\"sha256\":\"" + SHA + "\",\"size\":"
                        + (UpdatePolicy.MAX_APK_BYTES + 1) + "}}")));
    }

    @Test
    public void neverDowngradesOrReinstalls() {
        assertTrue(UpdatePolicy.acceptable(offer(45), 44));
        assertFalse(UpdatePolicy.acceptable(offer(44), 44));
        assertFalse(UpdatePolicy.acceptable(offer(43), 44));
        assertFalse(UpdatePolicy.acceptable(null, 44));
    }

    // --- download on Wi-Fi; mobile data only when the user allows it ---

    @Test
    public void downloadsOnWifiAndOnMobileDataOnlyWhenAllowed() {
        assertTrue(UpdatePolicy.mayDownload(true, false));
        assertFalse("mobile data is off by default", UpdatePolicy.mayDownload(false, false));
        assertTrue("his setting allows mobile data", UpdatePolicy.mayDownload(false, true));
        assertTrue(UpdatePolicy.mayDownload(true, true));
    }

    // --- verification ---

    @Test
    public void installsOnlyTheOfferedFileSignedLikeThisApp() {
        assertTrue(UpdatePolicy.verified(offer(45), SHA, 1000, PKG, 45, PKG, SIGNER, SIGNER));
        assertFalse("wrong hash", UpdatePolicy.verified(offer(45), SHA.replace('0', '1'), 1000, PKG, 45, PKG, SIGNER, SIGNER));
        assertFalse("wrong size", UpdatePolicy.verified(offer(45), SHA, 999, PKG, 45, PKG, SIGNER, SIGNER));
        assertFalse("other app", UpdatePolicy.verified(offer(45), SHA, 1000, "org.example.other", 45, PKG, SIGNER, SIGNER));
        assertFalse("other version", UpdatePolicy.verified(offer(45), SHA, 1000, PKG, 46, PKG, SIGNER, SIGNER));
        assertFalse("other key", UpdatePolicy.verified(offer(45), SHA, 1000, PKG, 45, PKG, OTHER_SIGNER, SIGNER));
        assertFalse("unreadable signer", UpdatePolicy.verified(offer(45), SHA, 1000, PKG, 45, PKG, null, SIGNER));
        assertFalse("installed signer unknown", UpdatePolicy.verified(offer(45), SHA, 1000, PKG, 45, PKG, SIGNER, null));
    }

    // --- opening the app installs at once, unless the user is dictating ---

    @Test
    public void openingTheAppInstallsAVerifiedUpdateUnlessDictating() {
        assertEquals(UpdatePolicy.InstallGate.OK, UpdatePolicy.mayInstallOnOpen(true, false));
        assertEquals(UpdatePolicy.InstallGate.DICTATING, UpdatePolicy.mayInstallOnOpen(true, true));
        assertEquals(UpdatePolicy.InstallGate.NOT_VERIFIED, UpdatePolicy.mayInstallOnOpen(false, false));
    }

    // --- a committed install: installing, then a Play Protect hold, then lost ---

    @Test
    public void aCommitUnfinishedForTwoMinutesIsAPlayProtectHold() {
        long t = 2_000_000_000L;
        String committed = UpdatePolicy.STATE_COMMITTED;
        assertEquals(UpdatePolicy.Progress.INSTALLING, UpdatePolicy.progress(committed, t, t, true));
        assertEquals(UpdatePolicy.Progress.INSTALLING,
                UpdatePolicy.progress(committed, t, t + UpdatePolicy.HOLD_AFTER_MS - 1, true));
        assertEquals(UpdatePolicy.Progress.HELD, UpdatePolicy.progress(committed, t, t + UpdatePolicy.HOLD_AFTER_MS, true));
        assertEquals("still held", UpdatePolicy.Progress.HELD,
                UpdatePolicy.progress(UpdatePolicy.STATE_HELD, t, t + 10 * 60_000L, true));
    }

    @Test
    public void aHoldIsLostOnlyAfterHalfAnHourWithItsSessionGone() {
        long t = 2_000_000_000L;
        String held = UpdatePolicy.STATE_HELD;
        assertEquals("the dialog may still be up", UpdatePolicy.Progress.HELD,
                UpdatePolicy.progress(held, t, t + UpdatePolicy.LOST_AFTER_MS, true));
        assertEquals(UpdatePolicy.Progress.HELD, UpdatePolicy.progress(held, t, t + UpdatePolicy.LOST_AFTER_MS - 1, false));
        assertEquals(UpdatePolicy.Progress.LOST, UpdatePolicy.progress(held, t, t + UpdatePolicy.LOST_AFTER_MS, false));
        assertEquals("a clock that went back is never lost", UpdatePolicy.Progress.INSTALLING,
                UpdatePolicy.progress(held, t, t - 60_000L, false));
    }

    @Test
    public void onlyACommittedInstallHasProgress() {
        long t = 2_000_000_000L;
        assertEquals(UpdatePolicy.Progress.NONE, UpdatePolicy.progress("", t, t + 3_600_000L, false));
        assertEquals(UpdatePolicy.Progress.NONE, UpdatePolicy.progress(UpdatePolicy.STATE_DOWNLOADED, t, t + 3_600_000L, false));
        assertEquals("waiting for a tap is the same unfinished install", UpdatePolicy.Progress.LOST,
                UpdatePolicy.progress(UpdatePolicy.STATE_NEEDS_USER, t, t + 3_600_000L, false));
    }

    @Test
    public void holdsAreReportedApartFromErrors() throws Exception {
        UpdateEvidence evidence = UpdateEvidence.fresh(47, 1_000L);
        evidence.playProtectHolds = 2;
        UpdateEvidence back = UpdateEvidence.fromJson(evidence.toJson());
        assertEquals(2, back.playProtectHolds);
        assertEquals(0, back.errorCount());
        JSONObject status = StatusReport.build(new Enrolment("ph0a1b2c3d", "milo", "https://vf.example.org"),
                "0.8.1", "Android 17", 2_000L, back);
        JSONObject update = status.getJSONObject("update");
        assertEquals(2, update.getJSONObject("holds").getInt("play-protect-hold"));
        JSONObject errors = update.getJSONObject("errors");
        assertEquals(0, errors.getInt("install") + errors.getInt("auth") + errors.getInt("validation"));
    }

    @Test
    public void dictationCountsAsBusyAndForAMinuteAfter() {
        long now = 10_000_000L;
        DictationActivity.report(true, now);
        assertTrue(DictationActivity.busy(now + 5 * 60_000L));
        DictationActivity.report(false, now);
        assertTrue(DictationActivity.busy(now + 30_000L));
        assertFalse(DictationActivity.busy(now + DictationActivity.QUIET_AFTER_MS + 1));
    }

    @Test
    public void gatewayStatusesMapToTheCanaryErrorCategories() {
        assertEquals(UpdateEvidence.ERROR_AUTH, GatewayClient.errorKind(401));
        assertEquals(UpdateEvidence.ERROR_AUTH, GatewayClient.errorKind(403));
        assertEquals(UpdateEvidence.ERROR_VALIDATION, GatewayClient.errorKind(422));
        assertEquals(UpdateEvidence.ERROR_VALIDATION, GatewayClient.errorKind(409));
        // Busy, rate-limited, server errors and "no such release" are not the phone's fault.
        assertEquals("", GatewayClient.errorKind(429));
        assertEquals("", GatewayClient.errorKind(503));
        assertEquals("", GatewayClient.errorKind(404));
    }
}
