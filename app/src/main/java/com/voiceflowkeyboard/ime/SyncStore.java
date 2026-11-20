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
        // Another user's (or gateway's) offline word lists never stay on the phone.
        WordLists.removeOtherCopies(context, enrolment);
        return enrolment;
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
