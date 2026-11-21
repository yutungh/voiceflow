package com.voiceflowkeyboard.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After an update: report the new version straight away and keep the hourly job.
 * If the user started the update by opening the app a moment ago, open it again in
 * the new version (Android may refuse a start from the background; then the user
 * simply opens it himself).
 */
public final class PackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            return;
        }
        SyncScheduler.kick(context);
        if (SyncStore.takeRelaunch(context, System.currentTimeMillis())) {
            try {
                Intent open = new Intent(context, SettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(SettingsActivity.EXTRA_JUST_UPDATED, true);
                context.startActivity(open);
            } catch (RuntimeException ignored) {
                // Not allowed from the background on this phone; nothing else to do.
            }
        }
    }
}
