package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * One sync run. Only one ever runs at a time, whatever started it. In order:
 * <ol>
 *   <li>ask the gateway which release this phone may install (one request, a
 *       bodiless 304 when nothing changed), and on Wi-Fi, or on mobile data when
 *       the user allows it, download and verify a newer one;</li>
 *   <li>note how a committed install is going (a two-minute wait is a Play
 *       Protect hold);</li>
 *   <li>write this phone's status (version, counters, errors, holds, pending update);</li>
 *   <li>if a verified update waits, say so once in a quiet notification.</li>
 * </ol>
 * It never installs. Google Play Protect stops every sideloaded update with its
 * own dialog, which in the background would wait unseen on the lock screen. The
 * install starts when the user opens the app ({@link #installOnOpen}). A network
 * failure is normal and is never counted as an error.
 */
final class SyncWorker {
    /** What opening the app did about an update. */
    enum Install {
        /** No verified update is waiting. */
        NOTHING,
        /** The user is dictating; the next open tries again. */
        WAITING,
        /** Handed to Android just now; the app restarts when it is done. */
        STARTED,
        /** An earlier commit is still going (or waits for Play Protect): never committed twice. */
        IN_PROGRESS,
        /** The file failed its checks or the session could not be made; tried again on the next open. */
        FAILED
    }

    private static final Object RUN_LOCK = new Object();
    /** How long after an install the user started by opening the app it may reopen itself. */
    static final long RELAUNCH_WINDOW_MS = 3 * 60_000L;

    private SyncWorker() {
    }

    static void run(Context context) {
        synchronized (RUN_LOCK) {
            SyncStore.Credentials credentials = SyncStore.credentials(context);
            int installed = SelfUpdater.installedVersionCode(context);
            // A GitHub word source needs no gateway: its words are read either way.
            if (credentials == null) {
                if (SyncStore.SOURCE_GITHUB.equals(SyncStore.wordSource(context))) {
                    refreshWordLists(context, null, installed);
                }
                return;
            }
            Enrolment enrolment = credentials.enrolment;
            String token;
            try {
                token = credentials.token();
            } catch (Exception e) {
                SyncStore.updateEvidence(context, installed, evidence -> evidence.lastError = "token-unreadable");
                if (SyncStore.SOURCE_GITHUB.equals(SyncStore.wordSource(context))) {
                    refreshWordLists(context, null, installed);
                }
                return;
            }
            removeInstalledDownloads(context, installed);
            // Sessions this app no longer tracks (a crash mid-install) are abandoned.
            SelfUpdater.abandonOthers(context, SyncStore.evidence(context, installed, System.currentTimeMillis()).pendingSessionId);
            GatewayClient client = new GatewayClient(enrolment.gateway, token);
            refreshWordLists(context, client, installed);
            checkRelease(context, client, installed);
            noteInstallProgress(context);
            reportStatus(context, client, enrolment, installed);
            if (updateReady(context)) {
                UpdateNotifier.readyOnce(context, SyncStore.pendingOffer(context));
            }
        }
    }

    static File apkFile(Context context, int versionCode) {
        File dir = new File(SyncStore.syncDir(context), "apk");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("could not create the download folder");
        }
        return new File(dir, versionCode + ".apk");
    }

    /** A downloaded, verified update is waiting, in any pending state. Cheap: no hashing. */
    static boolean updateWaiting(Context context) {
        int installed = SelfUpdater.installedVersionCode(context);
        UpdatePolicy.Offer offer = SyncStore.pendingOffer(context);
        if (offer == null || !UpdatePolicy.acceptable(offer, installed)) {
            return false;
        }
        UpdateEvidence evidence = SyncStore.evidence(context, installed, System.currentTimeMillis());
        File apk = new File(new File(SyncStore.syncDir(context), "apk"), offer.versionCode + ".apk");
        return evidence.pendingVersionCode == offer.versionCode
                && !evidence.pendingState.isEmpty()
                && apk.isFile()
                && apk.length() == offer.size;
    }

    /** Waiting and not yet started: what the "update ready" notification and the Install button mean. */
    static boolean updateReady(Context context) {
        if (!updateWaiting(context)) {
            return false;
        }
        String state = SyncStore.evidence(context, SelfUpdater.installedVersionCode(context), System.currentTimeMillis()).pendingState;
        return UpdatePolicy.STATE_DOWNLOADED.equals(state);
    }

    private static void checkRelease(Context context, GatewayClient client, int installed) {
        UpdatePolicy.Offer offer;
        try {
            GatewayClient.Release release = client.releaseOffer(SyncStore.releaseEtag(context));
            JSONObject body = release.notModified ? SyncStore.cachedRelease(context) : release.body;
            if (!release.notModified) {
                SyncStore.storeRelease(context, release.etag, release.body);
            }
            offer = UpdatePolicy.parseOffer(body);
        } catch (IOException e) {
            recordFailure(context, installed, e);
            return;
        }
        long now = System.currentTimeMillis();
        UpdateEvidence evidence = SyncStore.updateEvidence(context, installed, e -> e.lastCheckAt = now);
        if (!UpdatePolicy.acceptable(offer, installed)) {
            if (evidence.pendingVersionCode != 0) {
                // The release was held or withdrawn: drop what was downloaded for it,
                // including an install session still waiting for a tap.
                dropPending(context, installed, evidence);
            }
            return;
        }
        File apk = apkFile(context, offer.versionCode);
        UpdatePolicy.Offer kept = SyncStore.pendingOffer(context);
        boolean haveIt = evidence.pendingVersionCode == offer.versionCode
                && !evidence.pendingState.isEmpty()
                && kept != null
                && kept.versionCode == offer.versionCode
                && kept.sha256.equals(offer.sha256)
                && apk.isFile()
                && apk.length() == offer.size;
        if (haveIt) {
            return;
        }
        if (evidence.pendingVersionCode != 0) {
            // A different version (or the same one re-cut with other bytes) replaces it.
            dropPending(context, installed, evidence);
        }
        if (!UpdatePolicy.mayDownload(SelfUpdater.unmetered(context), Prefs.updatesOnMobileData(context))) {
            return;
        }
        try {
            String sha = client.downloadApk(offer, apk);
            if (!verifiedFile(context, offer, apk, sha)) {
                boolean unreadable = SelfUpdater.inspect(context, apk) == null;
                discard(context, offer.versionCode);
                SyncStore.updateEvidence(context, installed, e -> e.recordError(UpdateEvidence.ERROR_INSTALL,
                        unreadable ? "apk-unreadable" : "apk-unverified"));
                return;
            }
            SyncStore.storePendingOffer(context, offer);
            SyncStore.updateEvidence(context, installed, e -> {
                e.pendingVersionCode = offer.versionCode;
                e.pendingState = UpdatePolicy.STATE_DOWNLOADED;
                e.installAttempts = 0;
                e.lastInstallAttemptAt = 0;
            });
        } catch (IOException e) {
            discard(context, offer.versionCode);
            recordFailure(context, installed, e);
        }
    }

    private static void dropPending(Context context, int installed, UpdateEvidence evidence) {
        int stale = evidence.pendingSessionId;
        SyncStore.updateEvidence(context, installed, UpdateEvidence::clearPending);
        SyncStore.storePendingOffer(context, null);
        SelfUpdater.abandon(context, stale);
        UpdateNotifier.cancelReady(context);
        discard(context, evidence.pendingVersionCode);
    }

    /** The file is the offered release (hash, size, package, versionCode) and signed like this app. */
    private static boolean verifiedFile(Context context, UpdatePolicy.Offer offer, File apk, String sha) {
        SelfUpdater.ApkFacts facts = SelfUpdater.inspect(context, apk);
        return facts != null && UpdatePolicy.verified(
                offer,
                sha,
                apk.length(),
                facts.packageName,
                facts.versionCode,
                context.getPackageName(),
                facts.signerSha256,
                SelfUpdater.installedSignerSha256(context)
        );
    }

    /**
     * The word lists from the active word source (the sync server, or a GitHub
     * repository), into the offline copy. Content problems (a rejected file, a
     * missing layer) go to the status file's profile block only; a refused token
     * counts like any refused request. Never stops the run. {@code gateway} is null
     * when this phone has no gateway.
     */
    static void refreshWordLists(Context context, GatewayClient gateway, int installed) {
        try {
            Enrolment words = SyncStore.wordEnrolment(context);
            if (words == null) {
                return;
            }
            WordSource source;
            if (SyncStore.SOURCE_GITHUB.equals(SyncStore.wordSource(context))) {
                SyncStore.GitHubConfig config = SyncStore.gitHub(context);
                String token;
                try {
                    token = config.token();
                } catch (Exception e) {
                    SyncStore.updateEvidence(context, installed, ev -> ev.lastError = "github-token-unreadable");
                    return;
                }
                source = new GitHubClient(config.owner, config.repo, config.branch, config.user, words.device, token,
                        SyncStore.gitHubWait(context));
            } else if (gateway != null) {
                source = gateway;
            } else {
                return;
            }
            WordLists.refresh(context, source, words);
        } catch (WordLists.AuthRefused e) {
            if (e.cause.code.startsWith("github-")) {
                // A GitHub token is not the gateway's: noted, never counted in the update
                // evidence the gateway's rollout rule reads. The status block names it.
                SyncStore.updateEvidence(context, installed, ev -> ev.lastError = e.cause.code);
            } else {
                recordFailure(context, installed, e.cause);
            }
        } catch (RuntimeException | LinkageError e) {
            // LinkageError too: a word-list class that cannot load (0.8.2's regex threw
            // ExceptionInInitializerError) must not stop the release check or the status upload.
            SyncStore.updateEvidence(context, installed, ev -> ev.lastError = "words-failed");
        }
    }

    private static JSONObject wordListsBlock(Context context) {
        try {
            return WordLists.statusBlock(context);
        } catch (org.json.JSONException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static void reportStatus(Context context, GatewayClient client, Enrolment enrolment, int installed) {
        try {
            UpdateEvidence evidence = SyncStore.evidence(context, installed, System.currentTimeMillis());
            JSONObject status = StatusReport.build(
                    enrolment,
                    SelfUpdater.installedVersionName(context),
                    "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                    System.currentTimeMillis(),
                    evidence,
                    wordListsBlock(context)
            );
            client.putStatus(status);
        } catch (IOException e) {
            recordFailure(context, installed, e);
        } catch (org.json.JSONException e) {
            SyncStore.updateEvidence(context, installed, ev -> ev.lastError = "status-unbuildable");
        }
    }

    /**
     * How a committed install is going. Two minutes unfinished: a Play Protect
     * hold, counted once per attempt and never as an error. Thirty minutes with
     * its session gone: lost, so it becomes "downloaded" again and the next open
     * retries it. Returns the progress for the screen to show.
     */
    static UpdatePolicy.Progress noteInstallProgress(Context context) {
        synchronized (RUN_LOCK) {
            int installed = SelfUpdater.installedVersionCode(context);
            long now = System.currentTimeMillis();
            UpdateEvidence evidence = SyncStore.evidence(context, installed, now);
            UpdatePolicy.Progress progress = UpdatePolicy.progress(
                    evidence.pendingState, evidence.lastInstallAttemptAt, now, sessionAlive(context, evidence.pendingSessionId));
            if (progress == UpdatePolicy.Progress.HELD && !UpdatePolicy.STATE_HELD.equals(evidence.pendingState)) {
                SyncStore.updateEvidence(context, installed, e -> {
                    if (UpdatePolicy.STATE_COMMITTED.equals(e.pendingState)
                            || UpdatePolicy.STATE_NEEDS_USER.equals(e.pendingState)) {
                        e.pendingState = UpdatePolicy.STATE_HELD;
                        e.playProtectHolds++;
                    }
                });
            } else if (progress == UpdatePolicy.Progress.LOST) {
                SyncStore.updateEvidence(context, installed, e -> {
                    // It waited past two minutes too: a hold no run had counted yet.
                    if (!UpdatePolicy.STATE_HELD.equals(e.pendingState)) {
                        e.playProtectHolds++;
                    }
                    e.recordError(UpdateEvidence.ERROR_INSTALL, "install-lost");
                    e.pendingState = UpdatePolicy.STATE_DOWNLOADED;
                    e.pendingSessionId = 0;
                });
            }
            return progress;
        }
    }

    /**
     * The user tapped Try again on an install stuck behind Play Protect (its dialog was
     * closed): the old session is given up, not counted as an error, so the next
     * {@link #installOnOpen} commits afresh.
     */
    static void giveUpStuckInstall(Context context) {
        synchronized (RUN_LOCK) {
            // A hold the screen showed but no run has counted yet is counted first.
            noteInstallProgress(context);
            int installed = SelfUpdater.installedVersionCode(context);
            UpdateEvidence evidence = SyncStore.evidence(context, installed, System.currentTimeMillis());
            if (!UpdatePolicy.STATE_COMMITTED.equals(evidence.pendingState)
                    && !UpdatePolicy.STATE_HELD.equals(evidence.pendingState)
                    && !UpdatePolicy.STATE_NEEDS_USER.equals(evidence.pendingState)) {
                return;
            }
            int stale = evidence.pendingSessionId;
            SyncStore.updateEvidence(context, installed, e -> {
                e.pendingState = UpdatePolicy.STATE_DOWNLOADED;
                e.pendingSessionId = 0;
            });
            SelfUpdater.abandon(context, stale);
        }
    }

    private static boolean sessionAlive(Context context, int sessionId) {
        if (sessionId <= 0) {
            return false;
        }
        try {
            PackageInstaller.SessionInfo info = context.getPackageManager().getPackageInstaller().getSessionInfo(sessionId);
            return info != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Installs the waiting update because the user opened the app (or tapped "update
     * ready"). Not while the user is dictating, and never on top of an install that is
     * still going or waiting for Play Protect: committing again would abandon the
     * session its dialog belongs to. Every hash, signature and versionCode check
     * runs again on the file first, and "not dictating" is asked again just
     * before the commit.
     */
    static Install installOnOpen(Context context, java.util.function.BooleanSupplier stillOpen) {
        synchronized (RUN_LOCK) {
            // The wait for the lock (a sync run) can outlast the user's visit.
            if (!stillOpen.getAsBoolean()) {
                return Install.WAITING;
            }
            int installed = SelfUpdater.installedVersionCode(context);
            UpdatePolicy.Offer offer = SyncStore.pendingOffer(context);
            long now = System.currentTimeMillis();
            UpdateEvidence evidence = SyncStore.evidence(context, installed, now);
            if (offer == null
                    || !UpdatePolicy.acceptable(offer, installed)
                    || evidence.pendingVersionCode != offer.versionCode
                    || evidence.pendingState.isEmpty()) {
                return Install.NOTHING;
            }
            File apk = apkFile(context, offer.versionCode);
            if (!apk.isFile() || apk.length() != offer.size) {
                return Install.NOTHING;
            }
            UpdatePolicy.Progress progress = noteInstallProgress(context);
            if (progress == UpdatePolicy.Progress.INSTALLING || progress == UpdatePolicy.Progress.HELD) {
                return Install.IN_PROGRESS;
            }
            if (gate() != UpdatePolicy.InstallGate.OK || !stillOpen.getAsBoolean()) {
                return Install.WAITING;
            }
            String sha;
            try {
                sha = sha256(apk);
            } catch (IOException e) {
                return Install.FAILED;
            }
            if (!verifiedFile(context, offer, apk, sha)) {
                // Changed or damaged on disk since it was verified: fetch it again.
                SyncStore.updateEvidence(context, installed, e -> {
                    e.recordError(UpdateEvidence.ERROR_INSTALL, "apk-unverified");
                    e.clearPending();
                });
                SyncStore.storePendingOffer(context, null);
                UpdateNotifier.cancelReady(context);
                discard(context, offer.versionCode);
                return Install.FAILED;
            }
            // A session left from an attempt that ended (lost, or given up by Try again) is
            // forgotten first, so its "aborted" result is ignored, then abandoned.
            int stale = SyncStore.evidence(context, installed, now).pendingSessionId;
            SyncStore.updateEvidence(context, installed, e -> {
                e.pendingState = UpdatePolicy.STATE_COMMITTED;
                e.installAttempts++;
                e.lastInstallAttemptAt = now;
                e.pendingSessionId = 0;
            });
            SelfUpdater.abandon(context, stale);
            UpdateNotifier.cancelReady(context);
            SyncStore.markRelaunch(context, now + RELAUNCH_WINDOW_MS);
            try {
                // Asked again just before the commit: not dictating, and the app still open.
                SelfUpdater.commit(context, apk, offer,
                        () -> gate() == UpdatePolicy.InstallGate.OK && stillOpen.getAsBoolean(),
                        id -> SyncStore.updateEvidence(context, installed, e -> e.pendingSessionId = id),
                        true);
            } catch (SelfUpdater.ConditionsChanged e) {
                // The user started dictating, or left the app, while the file was copied: not a failure.
                SyncStore.updateEvidence(context, installed, ev -> {
                    ev.pendingSessionId = 0;
                    ev.pendingState = UpdatePolicy.STATE_DOWNLOADED;
                    ev.installAttempts = Math.max(0, ev.installAttempts - 1);
                });
                return Install.WAITING;
            } catch (IOException | RuntimeException e) {
                SyncStore.updateEvidence(context, installed, ev -> {
                    ev.pendingSessionId = 0;
                    ev.recordError(UpdateEvidence.ERROR_INSTALL, "install-session");
                    ev.pendingState = UpdatePolicy.STATE_DOWNLOADED;
                });
                return Install.FAILED;
            }
            return Install.STARTED;
        }
    }

    private static UpdatePolicy.InstallGate gate() {
        return UpdatePolicy.mayInstallOnOpen(true, DictationActivity.busy(System.currentTimeMillis()));
    }

    private static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return GatewayClient.hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no-sha256");
        }
    }

    private static void recordFailure(Context context, int installed, IOException e) {
        if (e instanceof GatewayClient.GatewayException) {
            GatewayClient.GatewayException refused = (GatewayClient.GatewayException) e;
            SyncStore.updateEvidence(context, installed, ev -> {
                if (refused.kind.isEmpty()) {
                    ev.lastError = refused.code;
                } else {
                    ev.recordError(refused.kind, refused.code);
                }
            });
            return;
        }
        // Offline, a timeout, a TLS failure: normal on a phone, never counted.
        SyncStore.updateEvidence(context, installed, ev -> ev.lastError = "offline");
    }

    /** An update that is now installed (or older) has no use: its download goes. */
    private static void removeInstalledDownloads(Context context, int installed) {
        File[] files = new File(SyncStore.syncDir(context), "apk").listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (!name.matches("[0-9]{1,9}\\.apk")) {
                continue;
            }
            if (Integer.parseInt(name.substring(0, name.length() - 4)) <= installed && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    private static void discard(Context context, int versionCode) {
        File apk = apkFile(context, versionCode);
        if (apk.exists() && !apk.delete()) {
            apk.deleteOnExit();
        }
    }
}
