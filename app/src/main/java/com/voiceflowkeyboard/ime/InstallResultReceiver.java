package com.voiceflowkeyboard.ime;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Hears how a self-update install went. Success usually never arrives here,
 * because the update restarts the app; the new version reports itself instead.
 * If Android asks for a tap after all, the request becomes a notification, the
 * documented fallback. Any other failure is counted as an install error for the
 * version that is running, and the attempt is retried later with a back-off.
 */
public final class InstallResultReceiver extends BroadcastReceiver {
    static final String CHANNEL_ID = "updates";
    static final int NOTIFICATION_ID = 7201;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SelfUpdater.ACTION_INSTALL_RESULT.equals(intent.getAction())) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int versionCode = intent.getIntExtra(SelfUpdater.EXTRA_VERSION_CODE, 0);
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        int installed = SelfUpdater.installedVersionCode(context);
        if (status != PackageInstaller.STATUS_SUCCESS) {
            // Only the session this phone is still waiting on counts. A result for one it
            // abandoned on purpose (a withdrawn release, a retry) is stale: no error, no
            // notification.
            UpdateEvidence now = SyncStore.evidence(context, installed, System.currentTimeMillis());
            if (sessionId <= 0 || now.pendingSessionId != sessionId || now.pendingVersionCode != versionCode) {
                return;
            }
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            synchronized (SelfUpdater.SESSION_LOCK) {
                UpdateEvidence after = SyncStore.updateEvidence(context, installed, evidence -> {
                    if (evidence.pendingSessionId == sessionId) {
                        evidence.pendingState = UpdatePolicy.STATE_NEEDS_USER;
                        evidence.lastError = "install-needs-user";
                    }
                });
                if (confirm != null && after.pendingSessionId == sessionId) {
                    // The user opened the app to update: Android's confirm screen comes up there
                    // and then. Otherwise (or if Android will not show it) a notification.
                    boolean onOpen = intent.getBooleanExtra(SelfUpdater.EXTRA_ON_OPEN, false);
                    if (!(onOpen && SettingsActivity.visible && showConfirmNow(context, confirm))) {
                        notifyNeedsTap(context, confirm, versionCode);
                    }
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            SyncStore.updateEvidence(context, installed, UpdateEvidence::clearPending);
            UpdateNotifier.cancelReady(context);
            return;
        }
        // Failed or refused (Play Protect's "Don't install" included): counted, and tried
        // again the next time the user opens the app; "update ready" may be said again.
        SyncStore.updateEvidence(context, installed, evidence -> {
            if (evidence.pendingSessionId != sessionId) {
                return;
            }
            evidence.recordError(UpdateEvidence.ERROR_INSTALL, "install-" + statusCode(status));
            evidence.pendingState = UpdatePolicy.STATE_DOWNLOADED;
            evidence.pendingSessionId = 0;
        });
        UpdateNotifier.allowAgain(context);
    }

    private static boolean showConfirmNow(Context context, Intent confirm) {
        try {
            Intent show = new Intent(confirm);
            show.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(show);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String statusCode(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "aborted";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "blocked";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "conflict";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "incompatible";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "invalid";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "storage";
            default:
                return "failed";
        }
    }

    private static void notifyNeedsTap(Context context, Intent confirm, int versionCode) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(
                context, versionCode, confirm, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic_24)
                .setContentTitle("VoiceLnq update ready")
                .setContentText("Tap to finish installing the new version.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
