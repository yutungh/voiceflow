package com.voiceflowkeyboard.ime;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

/**
 * When the sync worker runs. Every trigger goes through JobScheduler, so runs
 * never overlap and network work never delays a recording:
 * <ul>
 *   <li>the keyboard being shown, and the app being opened, together at most
 *       every 15 minutes (the reliable triggers: Samsung may defer the hourly
 *       job for sleeping apps);</li>
 *   <li>an enrolment, and the app having just been updated;</li>
 *   <li>an hourly persisted job that needs a network.</li>
 * </ul>
 * None of them installs: the background only checks, downloads and says
 * "update ready". Opening the app installs.
 */
final class SyncScheduler {
    static final int JOB_PERIODIC = 7101;
    static final int JOB_NOW = 7102;
    /** 0.8.0's background install jobs; cancelled wherever they are still scheduled. */
    static final int LEGACY_JOB_INSTALL_WINDOW = 7103;
    static final int LEGACY_JOB_INSTALL_NOW = 7104;
    static final long KEYBOARD_KICK_INTERVAL_MS = 15 * 60_000L;
    private static final long HOUR_MS = 3600_000L;

    private SyncScheduler() {
    }

    /** From the keyboard being shown: a run at most every 15 minutes, only when enrolled. */
    static void onKeyboardOpened(Context context) {
        try {
            if (SyncStore.enrolment(context) == null) {
                return;
            }
            if (SyncStore.claimKick(context, System.currentTimeMillis(), KEYBOARD_KICK_INTERVAL_MS)) {
                kick(context);
            }
        } catch (RuntimeException ignored) {
            // Scheduling must never disturb typing.
        }
    }

    /** From the app opening: the same 15-minute limit, shared with the keyboard. */
    static void onAppOpened(Context context) {
        onKeyboardOpened(context);
    }

    /** A run as soon as there is a network, plus the hourly job if it is missing. */
    static void kick(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler == null || SyncStore.enrolment(context) == null) {
            return;
        }
        ensurePeriodic(context, scheduler);
        scheduler.schedule(new JobInfo.Builder(JOB_NOW, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    static void ensurePeriodic(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler != null && SyncStore.enrolment(context) != null) {
            ensurePeriodic(context, scheduler);
        }
    }

    private static void ensurePeriodic(Context context, JobScheduler scheduler) {
        // An install job left from 0.8.0 would commit in the background, where Play
        // Protect's dialog waits unseen; it is not wanted any more.
        scheduler.cancel(LEGACY_JOB_INSTALL_WINDOW);
        scheduler.cancel(LEGACY_JOB_INSTALL_NOW);
        if (scheduler.getPendingJob(JOB_PERIODIC) != null) {
            return;
        }
        scheduler.schedule(new JobInfo.Builder(JOB_PERIODIC, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(HOUR_MS)
                .setPersisted(true)
                .build());
    }

    private static JobScheduler scheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, SyncJobService.class);
    }
}
