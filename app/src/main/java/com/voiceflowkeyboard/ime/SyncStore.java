package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * The phone's sync and update state, kept apart from the keyboard settings so
 * that a style reset or a settings change can never touch it.
 *
 * <ul>
 *   <li>Preferences file {@value #FILE}: device id, enrolment, the sealed token,
 *       which reset instructions have run.</li>
 *   <li>{@code files/sync/update-state.json}: the update evidence and counters
 *       ({@link UpdateEvidence}), written whole and renamed into place.</li>
 * </ul>
 */
final class SyncStore {
    static final String FILE = "voiceflow_sync";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_USER = "user";
    private static final String KEY_GATEWAY = "gateway";
    private static final String KEY_TOKEN = "token_sealed";
    private static final String KEY_ENROLLED_AT = "enrolled_at";
    private static final String KEY_RESETS_DONE = "style_resets_done";
    private static final String KEY_LAST_KICK = "last_kick_ms";
    private static final String KEY_RELEASE_ETAG = "release_etag";
    private static final String KEY_RELEASE_BODY = "release_body";
    private static final String KEY_RELAUNCH_UNTIL = "relaunch_until_ms";
    private static final String KEY_PENDING_OFFER = "pending_offer";
    /** Where the word lists come from: "" (the gateway when enrolled), "none", "gateway" or "github". */
    private static final String KEY_WORD_SOURCE = "word_source";
    private static final String KEY_GH_OWNER = "github_owner";
    private static final String KEY_GH_REPO = "github_repo";
    private static final String KEY_GH_BRANCH = "github_branch";
    private static final String KEY_GH_USER = "github_user";
    private static final String KEY_GH_TOKEN = "github_token_sealed";
    private static final String KEY_GH_NOT_BEFORE = "github_not_before_ms";

    static final String SOURCE_NONE = "none";
    static final String SOURCE_GATEWAY = "gateway";
    static final String SOURCE_GITHUB = "github";

    /** Swapped for a plain key in unit tests, where there is no Android Keystore. */
    static volatile TokenVault.KeySource keys = TokenVault.ANDROID_KEYSTORE;

    private static final Object LOCK = new Object();

    private SyncStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** This phone's device id, made the first time it is asked for and never changed. */
    static String deviceId(Context context) {
        // An id already made is read without the lock: the keyboard asks for it when a
        // dictation starts, and the lock is held by evidence writes that sync to disk.
        String existing = prefs(context).getString(KEY_DEVICE, "");
        if (Enrolment.validDevice(existing)) {
            return existing;
        }
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            String id = prefs.getString(KEY_DEVICE, "");
            if (Enrolment.validDevice(id)) {
                return id;
            }
            id = Enrolment.newDeviceId();
            if (!prefs.edit().putString(KEY_DEVICE, id).commit()) {
                throw new IllegalStateException("could not save the device id");
            }
            return id;
        }
    }

    /** The enrolment, or null when this phone has not been enrolled. */
    static Enrolment enrolment(Context context) {
        SharedPreferences prefs = prefs(context);
        String device = prefs.getString(KEY_DEVICE, "");
        String user = prefs.getString(KEY_USER, "");
        String gateway = prefs.getString(KEY_GATEWAY, "");
        if (!Enrolment.validDevice(device)
                || !Enrolment.validUser(user)
                || Enrolment.normalizeGateway(gateway) == null
                || prefs.getString(KEY_TOKEN, "").isEmpty()) {
            return null;
        }
        return new Enrolment(device, user, gateway);
    }

    /**
     * Stores an enrolment. The token is checked, sealed and only then written;
     * a bad value leaves the previous enrolment as it was.
     */
    static Enrolment enrol(Context context, String user, String gateway, String token) throws Exception {
        if (!Enrolment.validUser(user)) {
            throw new IllegalArgumentException("user must be a short lower-case name");
        }
        String origin = Enrolment.normalizeGateway(gateway);
        if (origin == null) {
            throw new IllegalArgumentException("gateway must be a bare https:// address");
        }
        if (!Enrolment.validToken(token)) {
            throw new IllegalArgumentException("token is not a vf-sync token");
        }
        String device = deviceId(context);
        String sealed = TokenVault.seal(keys, token);
        synchronized (LOCK) {
            boolean saved = prefs(context).edit()
                    .putString(KEY_USER, user)
                    .putString(KEY_GATEWAY, origin)
                    .putString(KEY_TOKEN, sealed)
                    .putLong(KEY_ENROLLED_AT, System.currentTimeMillis())
                    .commit();
            if (!saved) {
                throw new IllegalStateException("could not save the enrolment");
            }
        }
        Enrolment enrolment = new Enrolment(device, user, origin);
        // Another user's (or gateway's) offline word lists never stay on the phone. The
        // copy kept is the active word source's (a GitHub copy survives a gateway enrolment).
        WordLists.removeOtherCopies(context, wordEnrolment(context));
        return enrolment;
    }

    // ------------------------------------------------------------ word source

    /** A GitHub repository as the word source: what Settings > Word lists source saved. */
    static final class GitHubConfig {
        final String owner;
        final String repo;
        final String branch;
        final String user;
        final String sealedToken;

        GitHubConfig(String owner, String repo, String branch, String user, String sealedToken) {
            this.owner = owner;
            this.repo = repo;
            this.branch = branch;
            this.user = user;
            this.sealedToken = sealedToken;
        }

        /** The plain token, for one run. Never log or store what this returns. */
        String token() throws Exception {
            return TokenVault.open(keys, sealedToken);
        }

        String identity() {
            return GitHubClient.identity(owner, repo, branch);
        }
    }

    /** The saved GitHub settings with a token, or null. */
    static GitHubConfig gitHub(Context context) {
        SharedPreferences prefs = prefs(context);
        String owner = prefs.getString(KEY_GH_OWNER, "");
        String repo = prefs.getString(KEY_GH_REPO, "");
        String branch = prefs.getString(KEY_GH_BRANCH, "");
        String user = prefs.getString(KEY_GH_USER, "");
        String sealed = prefs.getString(KEY_GH_TOKEN, "");
        if (GitHubClient.parseRepo(owner + "/" + repo) == null || !GitHubClient.validBranch(branch)
                || !Enrolment.validUser(user) || sealed.isEmpty()) {
            return null;
        }
        return new GitHubConfig(owner, repo, branch, user, sealed);
    }

    /** What was chosen: "none", "gateway" or "github"; a phone that never chose uses the gateway. */
    static String wordSource(Context context) {
        String chosen = prefs(context).getString(KEY_WORD_SOURCE, "");
        return SOURCE_NONE.equals(chosen) || SOURCE_GITHUB.equals(chosen) ? chosen : SOURCE_GATEWAY;
    }

    /**
     * Who this phone is to the word lists it reads now, or null for none (the phone's
     * own list only). For GitHub, {@link Enrolment#gateway} carries the repository's
     * identity, which keys the offline copy.
     */
    static Enrolment wordEnrolment(Context context) {
        switch (wordSource(context)) {
            case SOURCE_NONE:
                return null;
            case SOURCE_GITHUB:
                return gitHubEnrolment(context, gitHub(context));
            default:
                return enrolment(context);
        }
    }

    /** The word-list identity of one GitHub configuration (null for none). */
    static Enrolment gitHubEnrolment(Context context, GitHubConfig config) {
        return config == null ? null : new Enrolment(deviceId(context), config.user, config.identity());
    }

    /** Anything to sync: the gateway (updates, status, perhaps words) or a GitHub word source. */
    static boolean anySync(Context context) {
        return enrolment(context) != null || (SOURCE_GITHUB.equals(wordSource(context)) && gitHub(context) != null);
    }

    /**
     * Saves a GitHub repository as the word source. A token is checked and sealed
     * first; an empty one keeps the token already saved. A bad value leaves
     * everything as it was.
     */
    static void saveGitHub(Context context, String repository, String branch, String user, String token) throws Exception {
        String[] repo = GitHubClient.parseRepo(repository);
        if (repo == null) {
            throw new IllegalArgumentException("Enter the repository as owner/name.");
        }
        String b = branch == null ? "" : branch.trim();
        if (!GitHubClient.validBranch(b)) {
            throw new IllegalArgumentException("Enter the branch name (usually main).");
        }
        String u = user == null ? "" : user.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Enrolment.validUser(u) || "household".equals(u)) {
            throw new IllegalArgumentException("Your name is lower-case letters, digits, - or _ (not household).");
        }
        String t = token == null ? "" : token.trim();
        String sealed = null;
        if (t.isEmpty()) {
            if (prefs(context).getString(KEY_GH_TOKEN, "").isEmpty()) {
                throw new IllegalArgumentException("Tap Connect GitHub, or paste an access token.");
            }
        } else if (!GitHubClient.validToken(t)) {
            throw new IllegalArgumentException("That doesn't look like a GitHub token (it starts with github_pat_).");
        } else {
            sealed = TokenVault.seal(keys, t);
        }
        deviceId(context);
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit()
                    .putString(KEY_GH_OWNER, repo[0])
                    .putString(KEY_GH_REPO, repo[1])
                    .putString(KEY_GH_BRANCH, b)
                    .putString(KEY_GH_USER, u)
                    .putString(KEY_WORD_SOURCE, SOURCE_GITHUB)
                    .remove(KEY_GH_NOT_BEFORE);
            // A credential already saved (a connection, rotated meanwhile, or a token) is
            // left exactly as it is: rewriting a copy read earlier could undo a rotation.
            if (sealed != null) {
                editor.putString(KEY_GH_TOKEN, sealed);
                gitHubGeneration++;
            }
            if (!editor.commit()) {
                throw new IllegalStateException("could not save the GitHub settings");
            }
        }
        WordLists.removeOtherCopies(context, wordEnrolment(context));
    }

    /** Chooses "none" or "gateway" (GitHub is chosen by {@link #saveGitHub}). */
    static void chooseWordSource(Context context, String source) {
        if (!SOURCE_NONE.equals(source) && !SOURCE_GATEWAY.equals(source)) {
            throw new IllegalArgumentException("not a source");
        }
        synchronized (LOCK) {
            prefs(context).edit().putString(KEY_WORD_SOURCE, source).commit();
        }
        WordLists.removeOtherCopies(context, wordEnrolment(context));
    }

    /** Removes the GitHub settings and their token; the phone goes back to the gateway, if enrolled. */
    static void forgetGitHub(Context context) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit()
                    .remove(KEY_GH_OWNER).remove(KEY_GH_REPO).remove(KEY_GH_BRANCH).remove(KEY_GH_USER)
                    .remove(KEY_GH_TOKEN).remove(KEY_GH_NOT_BEFORE);
            if (SOURCE_GITHUB.equals(prefs(context).getString(KEY_WORD_SOURCE, ""))) {
                editor.remove(KEY_WORD_SOURCE);
            }
            gitHubGeneration++;
            editor.commit();
        }
        GitHubAuth.cancelAll();
        WordLists.removeOtherCopies(context, wordEnrolment(context));
    }

    /**
     * Moves on with every explicit change of the GitHub credential (Connect, a pasted
     * token, Forget): work that read the credential before it never writes over the new
     * one ({@link GitHubCredentials}). In memory: a new process starts with nothing in flight.
     */
    private static long gitHubGeneration;

    static long gitHubGeneration() {
        synchronized (LOCK) {
            return gitHubGeneration;
        }
    }

    /** The sealed GitHub credential ("" for none). */
    static String gitHubSealed(Context context) {
        return prefs(context).getString(KEY_GH_TOKEN, "");
    }

    /**
     * Writes rotated tokens unless an explicit change came after {@code generation}
     * (then false, nothing written). Throws when the phone couldn't write them.
     */
    static boolean putGitHubSealed(Context context, long generation, String sealed) throws IOException {
        synchronized (LOCK) {
            if (generation != gitHubGeneration) {
                return false;
            }
            if (!prefs(context).edit().putString(KEY_GH_TOKEN, sealed).commit()) {
                throw new IOException("not saved");
            }
            return true;
        }
    }

    /** Connect GitHub: the App's tokens replace the credential (the repository is chosen at Save). */
    static void replaceGitHubSealed(Context context, String sealed) throws IOException {
        synchronized (LOCK) {
            gitHubGeneration++;
            if (!prefs(context).edit().putString(KEY_GH_TOKEN, sealed).commit()) {
                throw new IOException("not saved");
            }
        }
    }

    /** No request to GitHub before this time: GitHub asked to wait. */
    static GitHubClient.Wait gitHubWait(Context context) {
        return new GitHubClient.Wait() {
            @Override
            public long notBefore() {
                return gitHubNotBefore(context);
            }

            @Override
            public void setNotBefore(long millis) {
                prefs(context).edit().putLong(KEY_GH_NOT_BEFORE, millis).commit();
            }
        };
    }

    static long gitHubNotBefore(Context context) {
        return prefs(context).getLong(KEY_GH_NOT_BEFORE, 0L);
    }

    /** The saved GitHub fields for the settings screen (no token). */
    static String[] gitHubFields(Context context) {
        SharedPreferences prefs = prefs(context);
        String owner = prefs.getString(KEY_GH_OWNER, "");
        String repo = prefs.getString(KEY_GH_REPO, "");
        return new String[]{
                owner.isEmpty() ? "" : owner + "/" + repo,
                prefs.getString(KEY_GH_BRANCH, ""),
                prefs.getString(KEY_GH_USER, ""),
                prefs.getString(KEY_GH_TOKEN, "").isEmpty() ? "" : "stored"
        };
    }

    /** The enrolment and its sealed token, read together under the enrolment lock. */
    static final class Credentials {
        final Enrolment enrolment;
        final String sealedToken;

        Credentials(Enrolment enrolment, String sealedToken) {
            this.enrolment = enrolment;
            this.sealedToken = sealedToken;
        }

        /** The plain token, for one run. Never log or store what this returns. */
        String token() throws Exception {
            return TokenVault.open(keys, sealedToken);
        }
    }

    static Credentials credentials(Context context) {
        synchronized (LOCK) {
            Enrolment enrolment = enrolment(context);
            return enrolment == null ? null : new Credentials(enrolment, prefs(context).getString(KEY_TOKEN, ""));
        }
    }

    /** The plain token, for one request. Never log or store what this returns. */
    static String token(Context context) throws Exception {
        return TokenVault.open(keys, prefs(context).getString(KEY_TOKEN, ""));
    }

    static Set<String> resetsDone(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_RESETS_DONE, new HashSet<>()));
    }

    /** Records a reset as done, synchronously, before anything else happens. */
    static void markResetDone(Context context, String id) {
        synchronized (LOCK) {
            Set<String> done = resetsDone(context);
            done.add(id);
            if (!prefs(context).edit().putStringSet(KEY_RESETS_DONE, done).commit()) {
                throw new IllegalStateException("could not record the reset");
            }
        }
    }

    /** The ETag of the last release answer, "" when none is kept. */
    static String releaseEtag(Context context) {
        return prefs(context).getString(KEY_RELEASE_ETAG, "");
    }

    /** The last release answer, kept so a 304 can reuse it; null when none is kept. */
    static JSONObject cachedRelease(Context context) {
        String body = prefs(context).getString(KEY_RELEASE_BODY, "");
        if (body.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Keeps an answer with its ETag; an answer without one is not kept (no 304 can follow). */
    static void storeRelease(Context context, String etag, JSONObject body) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit();
            if (etag == null || etag.isEmpty() || body == null) {
                editor.remove(KEY_RELEASE_ETAG).remove(KEY_RELEASE_BODY);
            } else {
                editor.putString(KEY_RELEASE_ETAG, etag).putString(KEY_RELEASE_BODY, body.toString());
            }
            editor.apply();
        }
    }

    /**
     * The offer a downloaded, verified APK belongs to, so it can be installed later
     * without asking the gateway again (on app open, or when the screen goes off).
     * Null clears it.
     */
    static void storePendingOffer(Context context, UpdatePolicy.Offer offer) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit();
            if (offer == null) {
                editor.remove(KEY_PENDING_OFFER);
            } else {
                try {
                    JSONObject json = new JSONObject();
                    json.put("versionCode", offer.versionCode);
                    json.put("versionName", offer.versionName);
                    json.put("sha256", offer.sha256);
                    json.put("size", offer.size);
                    editor.putString(KEY_PENDING_OFFER, new JSONObject().put("offer", json).toString());
                } catch (JSONException e) {
                    editor.remove(KEY_PENDING_OFFER);
                }
            }
            editor.commit();
        }
    }

    /** The kept offer, re-validated by the same parser as the gateway's answer; null when none. */
    static UpdatePolicy.Offer pendingOffer(Context context) {
        String json = prefs(context).getString(KEY_PENDING_OFFER, "");
        if (json.isEmpty()) {
            return null;
        }
        try {
            return UpdatePolicy.parseOffer(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    /** Asks the next "app updated" signal, within {@code untilMs}, to open the app again. */
    static void markRelaunch(Context context, long untilMs) {
        prefs(context).edit().putLong(KEY_RELAUNCH_UNTIL, untilMs).commit();
    }

    /** Whether to reopen the app after an update; true at most once. */
    static boolean takeRelaunch(Context context, long now) {
        synchronized (LOCK) {
            long until = prefs(context).getLong(KEY_RELAUNCH_UNTIL, 0L);
            prefs(context).edit().remove(KEY_RELAUNCH_UNTIL).apply();
            return until > 0 && now <= until;
        }
    }

    /** True at most once per {@code intervalMs}: the keyboard-open and app-open triggers' rate limit. */
    static boolean claimKick(Context context, long now, long intervalMs) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            long last = prefs.getLong(KEY_LAST_KICK, 0L);
            if (last > 0 && now >= last && now - last < intervalMs) {
                return false;
            }
            prefs.edit().putLong(KEY_LAST_KICK, now).apply();
            return true;
        }
    }

    // ------------------------------------------------------------ evidence file

    static File syncDir(Context context) {
        File dir = new File(context.getFilesDir(), "sync");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("could not create the sync folder");
        }
        return dir;
    }

    private static File evidenceFile(Context context) {
        return new File(syncDir(context), "update-state.json");
    }

    /** The evidence for the version installed now; a different stored version starts fresh counters. */
    static UpdateEvidence evidence(Context context, int installedVersionCode, long now) {
        synchronized (LOCK) {
            UpdateEvidence stored = null;
            File file = evidenceFile(context);
            if (file.isFile()) {
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] bytes = new byte[(int) Math.min(file.length(), 256 * 1024)];
                    int read = 0;
                    while (read < bytes.length) {
                        int n = in.read(bytes, read, bytes.length - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                    stored = UpdateEvidence.fromJson(new JSONObject(new String(bytes, 0, read, StandardCharsets.UTF_8)));
                } catch (IOException | JSONException e) {
                    stored = null;
                }
            }
            return UpdateEvidence.forInstalled(stored, installedVersionCode, now);
        }
    }

    static void saveEvidence(Context context, UpdateEvidence evidence) {
        synchronized (LOCK) {
            File target = evidenceFile(context);
            File tmp = new File(target.getParentFile(), "update-state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(evidence.toJson().toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException | JSONException e) {
                return;
            }
            if (!tmp.renameTo(target)) {
                // Some file systems refuse to rename over an existing file.
                if (target.delete()) {
                    tmp.renameTo(target);
                }
            }
        }
    }

    /** Loads, changes and saves the evidence in one step. */
    interface EvidenceChange {
        void apply(UpdateEvidence evidence);
    }

    static UpdateEvidence updateEvidence(Context context, int installedVersionCode, EvidenceChange change) {
        synchronized (LOCK) {
            UpdateEvidence evidence = evidence(context, installedVersionCode, System.currentTimeMillis());
            change.apply(evidence);
            saveEvidence(context, evidence);
            return evidence;
        }
    }
}
