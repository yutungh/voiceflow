package com.voiceflowkeyboard.ime;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * "VoiceFlow update ready. Tap to install." A quiet notification, posted once
 * per downloaded version; tapping it opens the app, which installs the update.
 * It goes away when the install starts, the update is withdrawn, or it is done.
 */
final class UpdateNotifier {
    static final String CHANNEL_ID = "update_ready";
    static final int NOTIFICATION_ID = 7202;
    private static final String KEY_NOTIFIED_VERSION = "update_ready_notified";

    private UpdateNotifier() {
    }

    /** Posts the notice for this version unless it was posted before. */
    static void readyOnce(Context context, UpdatePolicy.Offer offer) {
        if (offer == null) {
            return;
        }
        android.content.SharedPreferences prefs = SyncStore.prefs(context);
        if (prefs.getInt(KEY_NOTIFIED_VERSION, 0) == offer.versionCode) {
            return;
        }
        if (post(context, offer)) {
            prefs.edit().putInt(KEY_NOTIFIED_VERSION, offer.versionCode).apply();
        }
    }

    /** After a failed install: the next run may say "ready" again. */
    static void allowAgain(Context context) {
        SyncStore.prefs(context).edit().remove(KEY_NOTIFIED_VERSION).apply();
    }

    static void cancelReady(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }

    private static boolean post(Context context, UpdatePolicy.Offer offer) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Updates ready", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(context, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(SettingsActivity.EXTRA_INSTALL_UPDATE, true);
        PendingIntent tap = PendingIntent.getActivity(
                context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ph_download_simple)
                .setContentTitle("VoiceFlow update ready")
                .setContentText("Tap to install.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
        return true;
    }
}
