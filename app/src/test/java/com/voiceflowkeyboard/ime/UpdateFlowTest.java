package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The update model after the Play Protect test: the background only checks,
 * downloads and says "update ready"; opening the app installs, never on top of
 * an install that is still going; a commit unfinished for two minutes is a
 * Play Protect hold, counted apart from errors; every file is checked again
 * before it is installed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class UpdateFlowTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private Context context;
    private int installed;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        installed = SelfUpdater.installedVersionCode(context);
        DictationActivity.report(false, 0);
    }

    @After public void tearDown() {
        DictationActivity.report(false, 0);
    }

    /** A downloaded file and its kept offer, in the given pending state. */
    private UpdatePolicy.Offer waiting(String state, long committedAt) throws Exception {
        UpdatePolicy.Offer offer = new UpdatePolicy.Offer(installed + 1, "9.9.9", SHA, 1000);
        File apk = SyncWorker.apkFile(context, offer.versionCode);
        try (FileOutputStream out = new FileOutputStream(apk)) {
            out.write(new byte[1000]);
        }
        SyncStore.storePendingOffer(context, offer);
        SyncStore.updateEvidence(context, installed, e -> {
            e.pendingVersionCode = offer.versionCode;
            e.pendingState = state;
            e.lastInstallAttemptAt = committedAt;
            e.pendingSessionId = 0;
        });
        return offer;
    }

    private UpdateEvidence evidence() {
        return SyncStore.evidence(context, installed, System.currentTimeMillis());
    }

    @Test public void nothingWaitingMeansNothingToInstall() {
        assertFalse(SyncWorker.updateWaiting(context));
        assertFalse(SyncWorker.updateReady(context));
        assertEquals(SyncWorker.Install.NOTHING, SyncWorker.installOnOpen(context, () -> true));
    }

    @Test public void aDownloadedUpdateIsReady() throws Exception {
        waiting(UpdatePolicy.STATE_DOWNLOADED, 0);
        assertTrue(SyncWorker.updateWaiting(context));
        assertTrue(SyncWorker.updateReady(context));
    }

    @Test public void aFileThatNoLongerMatchesIsNeverInstalledAndIsFetchedAgain() throws Exception {
        UpdatePolicy.Offer offer = waiting(UpdatePolicy.STATE_DOWNLOADED, 0);
        assertEquals(SyncWorker.Install.FAILED, SyncWorker.installOnOpen(context, () -> true));
        assertFalse("the bad file is gone", SyncWorker.apkFile(context, offer.versionCode).exists());
        assertNull(SyncStore.pendingOffer(context));
        assertEquals("", evidence().pendingState);
        assertEquals(1, evidence().installErrors);
        assertFalse(SyncWorker.updateWaiting(context));
    }

    @Test public void notWhileDictating() throws Exception {
        waiting(UpdatePolicy.STATE_DOWNLOADED, 0);
        DictationActivity.report(true, System.currentTimeMillis());
        assertEquals(SyncWorker.Install.WAITING, SyncWorker.installOnOpen(context, () -> true));
        assertEquals(UpdatePolicy.STATE_DOWNLOADED, evidence().pendingState);
    }

    @Test public void neverCommitsOverAnInstallThatIsStillGoing() throws Exception {
        waiting(UpdatePolicy.STATE_COMMITTED, System.currentTimeMillis() - 30_000L);
        assertEquals(SyncWorker.Install.IN_PROGRESS, SyncWorker.installOnOpen(context, () -> true));
        assertEquals(UpdatePolicy.STATE_COMMITTED, evidence().pendingState);
        assertFalse("committed is not 'ready'", SyncWorker.updateReady(context));
    }

    @Test public void twoMinutesUnfinishedIsAPlayProtectHoldCountedOnceAndNotAsAnError() throws Exception {
        waiting(UpdatePolicy.STATE_COMMITTED, System.currentTimeMillis() - UpdatePolicy.HOLD_AFTER_MS - 1_000L);
        assertEquals(UpdatePolicy.Progress.HELD, SyncWorker.noteInstallProgress(context));
        assertEquals(UpdatePolicy.STATE_HELD, evidence().pendingState);
        assertEquals(1, evidence().playProtectHolds);
        assertEquals(0, evidence().errorCount());
        SyncWorker.noteInstallProgress(context);
        assertEquals("counted once per attempt", 1, evidence().playProtectHolds);
        assertEquals("held, so still never committed twice", SyncWorker.Install.IN_PROGRESS, SyncWorker.installOnOpen(context, () -> true));
    }

    @Test public void aHoldWithItsSessionGoneForHalfAnHourIsLostAndRetriedOnOpen() throws Exception {
        waiting(UpdatePolicy.STATE_HELD, System.currentTimeMillis() - UpdatePolicy.LOST_AFTER_MS - 1_000L);
        assertEquals(UpdatePolicy.Progress.LOST, SyncWorker.noteInstallProgress(context));
        assertEquals(UpdatePolicy.STATE_DOWNLOADED, evidence().pendingState);
        assertEquals(1, evidence().installErrors);
        assertEquals("install-lost", evidence().lastError);
        assertTrue(SyncWorker.updateReady(context));
    }

    @Test public void neverInstallsOnceHeHasLeftTheApp() throws Exception {
        waiting(UpdatePolicy.STATE_DOWNLOADED, 0);
        assertEquals(SyncWorker.Install.WAITING, SyncWorker.installOnOpen(context, () -> false));
        assertEquals(UpdatePolicy.STATE_DOWNLOADED, evidence().pendingState);
        assertEquals(0, evidence().installAttempts);
    }

    @Test public void aSessionWaitingForATapIsStillThatInstallNeverCommittedOverAndCanBeHeld() throws Exception {
        waiting(UpdatePolicy.STATE_NEEDS_USER, System.currentTimeMillis() - 20_000L);
        assertFalse("waiting for a tap is not 'ready'", SyncWorker.updateReady(context));
        assertEquals(SyncWorker.Install.IN_PROGRESS, SyncWorker.installOnOpen(context, () -> true));
        SyncStore.updateEvidence(context, installed, e -> e.lastInstallAttemptAt = System.currentTimeMillis() - UpdatePolicy.HOLD_AFTER_MS - 1);
        assertEquals(UpdatePolicy.Progress.HELD, SyncWorker.noteInstallProgress(context));
        assertEquals(1, evidence().playProtectHolds);
        assertEquals(0, evidence().errorCount());
    }

    @Test public void tryAgainCountsAHoldTheScreenShowedBeforeGivingItUp() throws Exception {
        waiting(UpdatePolicy.STATE_COMMITTED, System.currentTimeMillis() - UpdatePolicy.HOLD_AFTER_MS - 1_000L);
        SyncWorker.giveUpStuckInstall(context);
        assertEquals(1, evidence().playProtectHolds);
        assertEquals(UpdatePolicy.STATE_DOWNLOADED, evidence().pendingState);
    }

    @Test public void aLostInstallNoRunHadCountedAsHeldCountsItsHold() throws Exception {
        waiting(UpdatePolicy.STATE_COMMITTED, System.currentTimeMillis() - UpdatePolicy.LOST_AFTER_MS - 1_000L);
        assertEquals(UpdatePolicy.Progress.LOST, SyncWorker.noteInstallProgress(context));
        assertEquals(1, evidence().playProtectHolds);
        assertEquals(UpdatePolicy.STATE_DOWNLOADED, evidence().pendingState);
    }

    @Test public void tryAgainGivesUpTheStuckSessionWithoutCountingAnError() throws Exception {
        waiting(UpdatePolicy.STATE_HELD, System.currentTimeMillis() - 5 * 60_000L);
        SyncWorker.giveUpStuckInstall(context);
        assertEquals(UpdatePolicy.STATE_DOWNLOADED, evidence().pendingState);
        assertEquals(0, evidence().errorCount());
    }

    @Test public void updateReadyIsNotifiedOncePerVersion() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        UpdatePolicy.Offer offer = waiting(UpdatePolicy.STATE_DOWNLOADED, 0);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        UpdateNotifier.readyOnce(context, offer);
        assertEquals(1, shadowOf(manager).size());
        assertEquals("VoiceLnq update ready",
                shadowOf(manager.getActiveNotifications()[0].getNotification()).getContentTitle().toString());
        assertEquals("Tap to install.",
                shadowOf(manager.getActiveNotifications()[0].getNotification()).getContentText().toString());
        UpdateNotifier.cancelReady(context);
        UpdateNotifier.readyOnce(context, offer);
        assertEquals("once per version", 0, shadowOf(manager).size());
        UpdateNotifier.allowAgain(context);
        UpdateNotifier.readyOnce(context, offer);
        assertEquals("again after a failed install", 1, shadowOf(manager).size());
    }

    @Test public void theKeptOfferSurvivesAndIsCheckedLikeTheGatewaysAnswer() {
        UpdatePolicy.Offer offer = new UpdatePolicy.Offer(48, "0.8.2", SHA, 1234);
        SyncStore.storePendingOffer(context, offer);
        UpdatePolicy.Offer back = SyncStore.pendingOffer(context);
        assertNotNull(back);
        assertEquals(48, back.versionCode);
        assertEquals(SHA, back.sha256);
        assertEquals(1234, back.size);
        SyncStore.storePendingOffer(context, null);
        assertNull(SyncStore.pendingOffer(context));
    }

    @Test public void theReleaseAnswerIsKeptOnlyWithItsEtag() throws Exception {
        JSONObject body = new JSONObject("{\"offer\":null}");
        SyncStore.storeRelease(context, "\"abc123\"", body);
        assertEquals("\"abc123\"", SyncStore.releaseEtag(context));
        assertEquals(body.toString(), SyncStore.cachedRelease(context).toString());
        SyncStore.storeRelease(context, "", body);
        assertEquals("", SyncStore.releaseEtag(context));
        assertNull(SyncStore.cachedRelease(context));
    }

    @Test public void onlyAPlainQuotedEtagIsSentBack() {
        assertTrue(GatewayClient.validEtag("\"abc123\""));
        assertTrue(GatewayClient.validEtag("W/\"abc123\""));
        assertFalse(GatewayClient.validEtag("abc123"));
        assertFalse(GatewayClient.validEtag("\"abc\"\r\nX-Evil: 1"));
        assertFalse(GatewayClient.validEtag("\"" + "a".repeat(120) + "\""));
    }

    @Test public void theAppReopensAfterAnUpdateOnlyOnceAndOnlyWhenHeJustStartedIt() {
        long now = 5_000_000L;
        assertFalse(SyncStore.takeRelaunch(context, now));
        SyncStore.markRelaunch(context, now + SyncWorker.RELAUNCH_WINDOW_MS);
        assertTrue(SyncStore.takeRelaunch(context, now + 1_000L));
        assertFalse("only once", SyncStore.takeRelaunch(context, now + 2_000L));
        SyncStore.markRelaunch(context, now);
        assertFalse("too late", SyncStore.takeRelaunch(context, now + 1));
    }

    @Test public void appOpenAndKeyboardShareTheFifteenMinuteCheckLimit() {
        long t = 9_000_000L;
        assertTrue(SyncStore.claimKick(context, t, SyncScheduler.KEYBOARD_KICK_INTERVAL_MS));
        assertFalse(SyncStore.claimKick(context, t + 60_000L, SyncScheduler.KEYBOARD_KICK_INTERVAL_MS));
        assertTrue(SyncStore.claimKick(context, t + SyncScheduler.KEYBOARD_KICK_INTERVAL_MS, SyncScheduler.KEYBOARD_KICK_INTERVAL_MS));
    }
}
