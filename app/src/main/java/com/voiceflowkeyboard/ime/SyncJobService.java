package com.voiceflowkeyboard.ime;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs {@link SyncWorker} off the main thread for every scheduled trigger. It
 * checks, downloads and reports; it never installs (opening the app does).
 */
public final class SyncJobService extends JobService {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public boolean onStartJob(JobParameters params) {
        WORKER.execute(() -> {
            try {
                SyncWorker.run(getApplicationContext());
            } catch (RuntimeException | LinkageError ignored) {
                // A broken run must not crash the keyboard's process; the next trigger tries again.
            }
            jobFinished(params, false);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The next trigger runs it again.
        return false;
    }
}
