package com.voiceflowkeyboard.ime;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * The Android half of the self-updater: reading this app's own version and
 * certificate, inspecting a downloaded APK, the device conditions, and the
 * PackageInstaller session. The rules themselves are in {@link UpdatePolicy}.
 */
final class SelfUpdater {
    static final String ACTION_INSTALL_RESULT = "com.voiceflowkeyboard.ime.action.INSTALL_RESULT";
    static final String EXTRA_VERSION_CODE = "versionCode";
    /** Set when he started the install by opening the app, so a confirm screen is shown there. */
    static final String EXTRA_ON_OPEN = "onOpen";

    /** What a downloaded APK says about itself. */
    static final class ApkFacts {
        final String packageName;
        final long versionCode;
        final String signerSha256;

        ApkFacts(String packageName, long versionCode, String signerSha256) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
        }
    }

    private SelfUpdater() {
    }

    @SuppressWarnings("deprecation")
    static int installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String installedVersionName(Context context) {
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** SHA-256 of this app's one signing certificate, or null if it has none or several. */
    @SuppressWarnings("deprecation")
    static String installedSignerSha256(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                return singleSigner(info.signingInfo == null ? null : signers(info.signingInfo));
            }
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            return singleSigner(info.signatures);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Reads the package name, versionCode and signer of an APK file; null if Android cannot read it. */
    @SuppressWarnings("deprecation")
    static ApkFacts inspect(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        }
        String signer = null;
        if (info != null && info.signingInfo != null) {
            signer = singleSigner(signers(info.signingInfo));
        }
        if (signer == null) {
            // Some releases leave signingInfo empty for an archive; the older flag still works there.
            PackageInfo legacy = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (legacy != null) {
                info = info == null ? legacy : info;
                signer = singleSigner(legacy.signatures);
            }
        }
        if (info == null) {
            return null;
        }
        long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
        return new ApkFacts(info.packageName, versionCode, signer);
    }

    private static Signature[] signers(SigningInfo signingInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        return signingInfo.hasMultipleSigners()
                ? signingInfo.getApkContentsSigners()
                : signingInfo.getSigningCertificateHistory() == null
                        ? null
                        : new Signature[]{latest(signingInfo.getSigningCertificateHistory())};
    }

    private static Signature latest(Signature[] history) {
        return history.length == 0 ? null : history[history.length - 1];
    }

    private static String singleSigner(Signature[] signatures) {
        if (signatures == null || signatures.length != 1 || signatures[0] == null) {
            return null;
        }
        try {
            return GatewayClient.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    // --------------------------------------------------------- device conditions

    static boolean screenOn(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        // Unknown counts as on: never install when we cannot tell.
        return power == null || power.isInteractive();
    }

    static boolean unmetered(Context context) {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            return false;
        }
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ------------------------------------------------------------------ install

    /**
     * Copies the APK into a PackageInstaller session, hashing it on the way, and
     * commits only if the bytes the session holds are the offered ones. Android
     * installs an app's update of itself without asking when the conditions in
     * {@code SessionParams#setRequireUserAction} hold; if it asks anyway, the
     * result arrives as STATUS_PENDING_USER_ACTION and becomes a notification.
     */
    /** Asked again after the copy, immediately before the commit. */
    interface CommitGate {
        boolean stillAllowed();
    }

    /** The conditions changed while the session was being prepared; not an error. */
    static final class ConditionsChanged extends IOException {
        ConditionsChanged() {
            super("conditions-changed");
        }
    }

    /** Held while a session is abandoned and while its result posts a notification. */
    static final Object SESSION_LOCK = new Object();

    /**
     * Abandons an install session and cancels its notification. The caller must
     * already have cleared the session id from the evidence, so the result that
     * Android reports for it (aborted) is recognised as stale and ignored.
     */
    static void abandon(Context context, int sessionId) {
        if (sessionId <= 0) {
            return;
        }
        synchronized (SESSION_LOCK) {
            try {
                context.getPackageManager().getPackageInstaller().abandonSession(sessionId);
            } catch (RuntimeException ignored) {
                // Already finished or gone.
            }
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(InstallResultReceiver.NOTIFICATION_ID);
            }
        }
    }

    /** Abandons every install session of this app except {@code keep} (orphans after a crash). */
    static void abandonOthers(Context context, int keep) {
        try {
            for (PackageInstaller.SessionInfo info : context.getPackageManager().getPackageInstaller().getMySessions()) {
                if (info.getSessionId() != keep) {
                    abandon(context, info.getSessionId());
                }
            }
        } catch (RuntimeException ignored) {
            // Nothing to tidy.
        }
    }

    /** Told the session id as soon as the session exists, before anything is copied. */
    interface SessionCreated {
        void created(int sessionId);
    }

    /** Returns the session id of the committed install. */
    static int commit(Context context, File apk, UpdatePolicy.Offer offer, CommitGate gate, SessionCreated created,
                      boolean onOpen) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER);
        }
        int sessionId = installer.createSession(params);
        created.created(sessionId);
        PackageInstaller.Session session = installer.openSession(sessionId);
        boolean committed = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            if (!offer.sha256.equals(GatewayClient.hex(digest.digest()))) {
                throw new IOException("apk-changed");
            }
            // Copying took a while: he may have woken the phone or started dictating.
            if (!gate.stillAllowed()) {
                throw new ConditionsChanged();
            }
            Intent intent = new Intent(context, InstallResultReceiver.class)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(context.getPackageName())
                    .putExtra(EXTRA_VERSION_CODE, offer.versionCode)
                    .putExtra(EXTRA_ON_OPEN, onOpen);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The installer fills in the status extras, so the intent must be mutable.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent result = PendingIntent.getBroadcast(context, sessionId, intent, flags);
            session.commit(result.getIntentSender());
            committed = true;
            return sessionId;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no-sha256");
        } finally {
            if (!committed) {
                session.abandon();
            }
            session.close();
        }
    }
}
