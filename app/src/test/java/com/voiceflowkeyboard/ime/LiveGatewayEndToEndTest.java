package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A manual end-to-end run of the phone's updater client against a real vf-sync
 * gateway with a test token and a staged test release. Skipped unless VF_E2E_DIR
 * is set (never in CI). VF_E2E_DIR holds {@code gateway.txt} (origin),
 * {@code token.txt}, {@code device.txt} and {@code user.txt}; the APK is written
 * there as {@code downloaded.apk} and a summary as {@code e2e.json}.
 * Installing the APK needs a phone and is not part of this test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LiveGatewayEndToEndTest {
    private static String read(File dir, String name) throws Exception {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8).trim();
    }

    private static String times(String s, int n) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            out.append(s);
        }
        return out.toString();
    }

    @Test
    public void statusOfferDownloadAndRefusals() throws Exception {
        String path = System.getenv("VF_E2E_DIR");
        assumeTrue(path != null && !path.isEmpty());
        File dir = new File(path);
        String origin = Enrolment.normalizeGateway(read(dir, "gateway.txt"));
        assertNotNull(origin);
        String token = read(dir, "token.txt");
        Enrolment me = new Enrolment(read(dir, "device.txt"), read(dir, "user.txt"), origin);
        JSONObject summary = new JSONObject();
        GatewayClient client = new GatewayClient(origin, token);

        // 1. Status, in the exact shape the phone sends.
        UpdateEvidence evidence = UpdateEvidence.fresh(44, System.currentTimeMillis());
        evidence.recordDictation();
        client.putStatus(StatusReport.build(me, "0.8.0", "Android (e2e from JVM)", System.currentTimeMillis(), evidence));
        summary.put("statusAccepted", true);

        // 2. The offer.
        UpdatePolicy.Offer offer = UpdatePolicy.parseOffer(client.releaseOffer());
        assertNotNull("the gateway offered no release", offer);
        assertTrue(UpdatePolicy.acceptable(offer, 44));
        assertFalse(UpdatePolicy.acceptable(offer, offer.versionCode));
        summary.put("offerVersionCode", offer.versionCode).put("offerSize", offer.size);

        // 3. Download: size-capped, hashed while streaming.
        File apk = new File(dir, "downloaded.apk");
        long start = System.currentTimeMillis();
        String sha = client.downloadApk(offer, apk);
        summary.put("downloadMs", System.currentTimeMillis() - start);
        assertEquals(offer.sha256, sha);
        assertEquals(offer.size, apk.length());
        summary.put("sha256Matches", true);

        // 4. The staged test release carries an APK whose own versionCode is 44:
        //    the phone's verification must refuse it.
        assertFalse(UpdatePolicy.verified(offer, sha, apk.length(), "com.voiceflowkeyboard.ime", 44,
                "com.voiceflowkeyboard.ime", times("a", 64), times("a", 64)));
        summary.put("versionMismatchRefused", true);

        // 5. Refusals map to the canary's error categories.
        try {
            new GatewayClient(origin, "vfs1." + times("x", 43)).releaseOffer();
            fail("a wrong token was accepted");
        } catch (GatewayClient.GatewayException e) {
            assertEquals(UpdateEvidence.ERROR_AUTH, e.kind);
            summary.put("wrongTokenStatus", e.status);
        }
        try {
            client.putStatus(StatusReport.build(new Enrolment("ph00000000", me.user, origin),
                    "0.8.0", "Android (e2e from JVM)", System.currentTimeMillis(), evidence));
            fail("a status for another device was accepted");
        } catch (GatewayClient.GatewayException e) {
            assertEquals(UpdateEvidence.ERROR_AUTH, e.kind);
            summary.put("otherDeviceStatus", e.status);
        }
        try (FileOutputStream out = new FileOutputStream(new File(dir, "e2e.json"))) {
            out.write(summary.toString(1).getBytes(StandardCharsets.UTF_8));
        }
    }
}
