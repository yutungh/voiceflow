package com.voiceflowkeyboard.ime;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The self-updater's rules, as pure functions so every one is unit-tested.
 *
 * <ul>
 *   <li>Take only an offer that is newer than what is installed: never a
 *       downgrade, never a reinstall.</li>
 *   <li>Download on an unmetered network (Wi-Fi), or on mobile data only when he
 *       has allowed it in Settings.</li>
 *   <li>Install only a file whose size and SHA-256 match the offer, whose package
 *       and versionCode match, and that is signed by exactly the certificate the
 *       installed app is signed by.</li>
 *   <li>Never install in the background. Google Play Protect holds every
 *       sideloaded update behind its own dialog, which would wait unseen on the
 *       lock screen; so the background only checks, downloads and verifies, and
 *       says "update ready" in a quiet notification.</li>
 *   <li>Install when he opens the VoiceFlow app itself (or taps that
 *       notification), unless he is dictating. He is looking at the phone, so
 *       Play Protect's dialog, if it shows, is in front of him.</li>
 *   <li>An install that has not finished two minutes after its commit is held
 *       by Play Protect: shown as such and counted as a hold, not an error. One
 *       still unfinished after 30 minutes, with its session gone, is lost and
 *       is tried again on the next open.</li>
 * </ul>
 */
final class UpdatePolicy {
    static final long MAX_APK_BYTES = 400L * 1024 * 1024;
    static final long HOUR_MS = 3600_000L;
    /** A committed install not finished after this is waiting for Play Protect. */
    static final long HOLD_AFTER_MS = 2 * 60_000L;
    /** A committed install not finished after this, with no session left, is lost. */
    static final long LOST_AFTER_MS = 30 * 60_000L;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION_NAME = Pattern.compile("[0-9A-Za-z._+-]{1,40}");

    /** One release the gateway offers this device. */
    static final class Offer {
        final int versionCode;
        final String versionName;
        final String sha256;
        final long size;

        Offer(int versionCode, String versionName, String sha256, long size) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    enum InstallGate {
        OK,
        NOT_VERIFIED,
        DICTATING
    }

    /** Where a committed install stands. */
    enum Progress {
        /** Nothing committed (none waiting, or downloaded and not started). */
        NONE,
        /** Committed under two minutes ago: "Updating VoiceFlow…". */
        INSTALLING,
        /** Committed and unfinished for two minutes or more: waiting for Play Protect. */
        HELD,
        /** Unfinished for 30 minutes with no session left: try again on the next open. */
        LOST
    }

    private UpdatePolicy() {
    }

    /**
     * Reads {@code GET /v1/release/android}: {"offer": null} or {"offer": {...}}.
     * Anything malformed is no offer. The offer's own URL is ignored: the APK is
     * fetched from the enrolled gateway's fixed route only.
     */
    static Offer parseOffer(JSONObject response) {
        if (response == null || response.isNull("offer")) {
            return null;
        }
        JSONObject offer = response.optJSONObject("offer");
        if (offer == null) {
            return null;
        }
        long versionCode = offer.optLong("versionCode", -1);
        String versionName = offer.optString("versionName", "");
        String sha = offer.optString("sha256", "").toLowerCase(Locale.ROOT);
        long size = offer.optLong("size", -1);
        if (versionCode < 1 || versionCode > 999_999_999
                || !VERSION_NAME.matcher(versionName).matches()
                || !SHA256.matcher(sha).matches()
                || size <= 0 || size > MAX_APK_BYTES) {
            return null;
        }
        return new Offer((int) versionCode, versionName, sha, size);
    }

    /** Only a strictly newer version is ever taken. */
    static boolean acceptable(Offer offer, int installedVersionCode) {
        return offer != null && offer.versionCode > installedVersionCode;
    }

    /** Wi-Fi always; mobile data only with his "Allow updates on mobile data" setting. */
    static boolean mayDownload(boolean unmetered, boolean mobileDataAllowed) {
        return unmetered || mobileDataAllowed;
    }

    /** Whether the downloaded file is the offered release, signed like this app. */
    static boolean verified(
            Offer offer,
            String fileSha256,
            long fileSize,
            String apkPackage,
            long apkVersionCode,
            String ownPackage,
            String apkSignerSha256,
            String installedSignerSha256
    ) {
        return offer != null
                && offer.sha256.equalsIgnoreCase(fileSha256 == null ? "" : fileSha256)
                && offer.size == fileSize
                && ownPackage != null && ownPackage.equals(apkPackage)
                && apkVersionCode == offer.versionCode
                && apkSignerSha256 != null && SHA256.matcher(apkSignerSha256).matches()
                && apkSignerSha256.equals(installedSignerSha256);
    }

    /** Opening the app: install now unless the file is unverified or he is dictating. */
    static InstallGate mayInstallOnOpen(boolean verified, boolean dictationBusy) {
        if (!verified) {
            return InstallGate.NOT_VERIFIED;
        }
        if (dictationBusy) {
            return InstallGate.DICTATING;
        }
        return InstallGate.OK;
    }

    /**
     * Where a committed install stands, from its pending state, when it was
     * committed and whether its PackageInstaller session still exists. A clock
     * that went backwards counts as "just committed", never as lost.
     */
    static Progress progress(String pendingState, long committedAt, long now, boolean sessionAlive) {
        // A session waiting for a confirmation tap is still that same install, unfinished.
        if (!STATE_COMMITTED.equals(pendingState) && !STATE_HELD.equals(pendingState)
                && !STATE_NEEDS_USER.equals(pendingState)) {
            return Progress.NONE;
        }
        long age = committedAt <= 0 || now < committedAt ? 0 : now - committedAt;
        if (age >= LOST_AFTER_MS && !sessionAlive) {
            return Progress.LOST;
        }
        return age >= HOLD_AFTER_MS ? Progress.HELD : Progress.INSTALLING;
    }

    /** The pending states an update moves through. */
    static final String STATE_DOWNLOADED = "downloaded";
    static final String STATE_COMMITTED = "committed";
    static final String STATE_HELD = "play-protect-hold";
    static final String STATE_NEEDS_USER = "needs-user";

}
