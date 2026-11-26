package com.voiceflowkeyboard.ime;

import android.content.Context;

/**
 * The phone's one GitHub credential, sealed in the Android Keystore ({@link TokenVault})
 * under {@code github_token_sealed}: either a pasted token (the Advanced path, kept raw as
 * before) or the VoiceFlow Word Lists App's tokens from Connect GitHub (a small JSON record,
 * {@link GitHubAuth.Tokens}). The phone only reads with either today.
 *
 * <ul>
 *   <li>The App's access token lasts 8 hours; it is refreshed on demand, ten minutes before
 *       it runs out. Refreshing rotates both tokens, so the new pair is stored before it is
 *       used, under one lock that re-reads the store: two callers never spend the same
 *       refresh token. If it can't be stored, it is kept in memory (the old pair no longer
 *       works) and stored at the next use.</li>
 *   <li>A 401: the refused token is compared with the current one first (someone may have
 *       rotated it already); otherwise the pair is refreshed once. A refused refresh means
 *       Connect GitHub again; the record is kept so the screen can say so.</li>
 *   <li>An explicit change (Connect, a pasted token, Forget) moves {@link SyncStore}'s
 *       generation on: work that started before it never writes over the new credential.</li>
 * </ul>
 * The lock here is never held while {@link SyncWorker}'s run lock is taken, and the
 * settings screen never waits on it from the main thread.
 */
final class GitHubCredentials {
    static final long REFRESH_MARGIN_MS = 10 * 60_000L;
    private static final Object LOCK = new Object();
    /** A credential newer than the stored one (it couldn't be stored), for this generation. */
    private static volatile String unsaved;
    private static volatile long unsavedGeneration = -1;

    private GitHubCredentials() {
    }

    /** A token for one piece of work. */
    static final class Access {
        final String token;
        /** The App's token (it can be refreshed); false for a pasted one. */
        final boolean app;
        final long generation;

        Access(String token, boolean app, long generation) {
            this.token = token;
            this.app = app;
            this.generation = generation;
        }
    }

    /** Why there is no token: "missing", "reconnect", "replaced", "store" or "retry". */
    static final class Problem extends Exception {
        final String code;

        Problem(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final String RECONNECT = "VoiceLnq's connection to GitHub has ended. Tap Connect GitHub to connect again.";

    private static Problem missing() {
        return new Problem("missing", "Tap Connect GitHub (or paste an access token) first.");
    }

    /** The credential's text, or null for none. */
    private static String load(Context context, long generation) throws Problem {
        if (unsaved != null && unsavedGeneration == generation) {
            return unsaved;
        }
        String sealed = SyncStore.gitHubSealed(context);
        if (sealed.isEmpty()) {
            return null;
        }
        try {
            return TokenVault.open(SyncStore.keys, sealed);
        } catch (Exception e) {
            throw new Problem("store", "The GitHub sign-in saved on this phone couldn't be read. Connect GitHub again.");
        }
    }

    /** Stores rotated tokens before anyone uses them; false when an explicit change came first. */
    private static boolean store(Context context, long generation, String text) {
        try {
            if (!SyncStore.putGitHubSealed(context, generation, TokenVault.seal(SyncStore.keys, text))) {
                return false;
            }
            unsaved = null;
            return true;
        } catch (Exception e) {
            unsaved = text;
            unsavedGeneration = generation;
            return true;
        }
    }

    private static void markReconnect(Context context, long generation, GitHubAuth.Tokens tokens) {
        store(context, generation, tokens.needingReconnect().toJson());
    }

    private static Access refreshLocked(Context context, long generation, GitHubAuth.Tokens tokens, long now) throws Problem {
        if (tokens.refresh.isEmpty() || tokens.refreshExpiresAt <= now) {
            markReconnect(context, generation, tokens);
            throw new Problem("reconnect", RECONNECT);
        }
        GitHubAuth.Refreshed refreshed = GitHubAuth.refresh(GitHubApp.CLIENT_ID, tokens.refresh, now);
        switch (refreshed.state) {
            case OK:
                if (!store(context, generation, refreshed.tokens.toJson())) {
                    throw new Problem("replaced", "The GitHub settings changed meanwhile. Try again.");
                }
                return new Access(refreshed.tokens.access, true, generation);
            case REJECTED:
                markReconnect(context, generation, tokens);
                throw new Problem("reconnect", RECONNECT);
            default:
                throw new Problem("retry", refreshed.error == null ? "Can't reach GitHub." : refreshed.error.getMessage());
        }
    }

    /** A token for one piece of work, refreshed first when it runs out within ten minutes. */
    static Access access(Context context, long now) throws Problem {
        synchronized (LOCK) {
            long generation = SyncStore.gitHubGeneration();
            String text = load(context, generation);
            if (text == null) {
                throw missing();
            }
            if (unsaved != null && unsavedGeneration == generation) {
                store(context, generation, unsaved);
            }
            GitHubAuth.Tokens tokens = GitHubAuth.Tokens.fromJson(text);
            if (tokens == null) {
                if (!GitHubClient.validToken(text)) {
                    throw missing();
                }
                return new Access(text, false, generation);
            }
            if (tokens.reconnect) {
                throw new Problem("reconnect", RECONNECT);
            }
            if (tokens.accessExpiresAt - REFRESH_MARGIN_MS > now) {
                return new Access(tokens.access, true, generation);
            }
            return refreshLocked(context, generation, tokens, now);
        }
    }

    /**
     * GitHub refused {@code failed} (401). If it was replaced or rotated meanwhile, the
     * current token comes back without asking GitHub; otherwise the pair is refreshed once.
     */
    static Access afterUnauthorized(Context context, Access failed, long now) throws Problem {
        synchronized (LOCK) {
            long generation = SyncStore.gitHubGeneration();
            if (generation != failed.generation) {
                throw new Problem("replaced", "The GitHub settings changed meanwhile. Try again.");
            }
            String text = load(context, generation);
            GitHubAuth.Tokens tokens = GitHubAuth.Tokens.fromJson(text);
            if (text == null) {
                throw missing();
            }
            if (tokens == null) {
                throw new Problem("replaced", "The GitHub settings changed meanwhile. Try again.");
            }
            if (tokens.reconnect) {
                throw new Problem("reconnect", RECONNECT);
            }
            if (!tokens.access.equals(failed.token)) {
                return new Access(tokens.access, true, generation);
            }
            return refreshLocked(context, generation, tokens, now);
        }
    }

    /** The still-current token refused again after its refresh: Connect GitHub again. */
    static void refusedAgain(Context context, Access failed) {
        synchronized (LOCK) {
            long generation = SyncStore.gitHubGeneration();
            if (generation != failed.generation) {
                return;
            }
            try {
                GitHubAuth.Tokens tokens = GitHubAuth.Tokens.fromJson(load(context, generation));
                if (tokens != null && tokens.access.equals(failed.token)) {
                    markReconnect(context, generation, tokens);
                }
            } catch (Problem ignored) {
                // Nothing readable to mark.
            }
        }
    }

    /** Connect GitHub finished: the App's tokens replace whatever credential was there. */
    static void connect(Context context, GitHubAuth.Tokens tokens) throws Exception {
        synchronized (LOCK) {
            SyncStore.replaceGitHubSealed(context, TokenVault.seal(SyncStore.keys, tokens.toJson()));
            unsaved = null;
        }
    }

    /** For the settings screen: "" (none), "pat", "app" or "reconnect". Reads only. */
    static String kind(Context context, long now) {
        String sealed = SyncStore.gitHubSealed(context);
        if (sealed.isEmpty()) {
            return "";
        }
        String text;
        try {
            text = unsaved != null && unsavedGeneration == SyncStore.gitHubGeneration() ? unsaved : TokenVault.open(SyncStore.keys, sealed);
        } catch (Exception e) {
            return "reconnect";
        }
        GitHubAuth.Tokens tokens = GitHubAuth.Tokens.fromJson(text);
        if (tokens == null) {
            return GitHubClient.validToken(text) ? "pat" : "";
        }
        return tokens.reconnect || (tokens.refreshExpiresAt <= now && tokens.accessExpiresAt <= now) ? "reconnect" : "app";
    }
}
