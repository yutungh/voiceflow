package com.voiceflowkeyboard.ime;

/**
 * Whether VoiceFlow is in the middle of a dictation (recording, transcribing,
 * tidying up or delivering). The self-updater never commits an install while it
 * is, or within a minute after, because an update restarts the app.
 *
 * <p>The keyboard service is the only owner of that state; it reports changes
 * here and this class only remembers them.
 */
final class DictationActivity {
    static final long QUIET_AFTER_MS = 60_000L;

    private static volatile boolean busy;
    private static volatile long lastChangeAt;

    private DictationActivity() {
    }

    static void report(boolean nowBusy, long now) {
        busy = nowBusy;
        lastChangeAt = now;
    }

    static boolean busy(long now) {
        long last = lastChangeAt;
        return busy || (last > 0 && now >= last && now - last < QUIET_AFTER_MS);
    }
}
