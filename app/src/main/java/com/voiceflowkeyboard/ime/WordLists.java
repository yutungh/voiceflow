package com.voiceflowkeyboard.ime;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * This phone's word lists: its own list (kept in the settings, exactly as
 * before) merged with the user's shared lists, read from the active
 * {@link WordSource} (the vf-sync gateway, or a private GitHub repository chosen
 * on the Word lists source screen) and kept in an offline copy.
 *
 * <h3>Precedence</h3>
 * built-in fixes &lt; household &lt; generated (from the user's notes) &lt; personal
 * &lt; this phone's list &lt; edits made in an app. The phone's list is never
 * written by any of this; it stays editable on the Word lists screen and still
 * drives typed-text corrections on its own.
 *
 * <h3>The offline copy</h3>
 * {@code files/sync/words/<key>/}, one folder per gateway and user (the key is a
 * hash of both): the last good copy of each layer file, every edit ever seen
 * (immutable, kept by name), the last manifest and a small state file. App
 * private; the app allows no backup. A bad, missing or unreachable file never
 * empties a layer. Another user's folder is removed as soon as the enrolment
 * names someone else.
 *
 * <h3>Until a synced copy exists</h3>
 * A phone that is not enrolled, or has never accepted a manifest from the
 * gateway, dictates exactly as 0.8.1 did ({@link DictationWords#legacy}).
 */
final class WordLists {
    private static final Object LOCK = new Object();
    private static final Pattern OP_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,99}\\.json$");
    /** Edits fetched per run at most (inside the gateway's burst of 120 requests); the rest come on the next run. */
    static final int MAX_OPS_PER_RUN = 100;
    /** Edits kept at most (the gateway lists at most twice its 20,000 limit). */
    static final int MAX_OPS_KEPT = 40_000;

    private static volatile Cached cached;
    /** The last snapshot built, kept through an invalidation so a recording never waits for a rebuild. */
    private static volatile Cached lastBuilt;
    private static volatile int generation;

    private WordLists() {
    }

    // ---------------------------------------------------------------- the phone's own layers

    /**
     * The built-in fixes every phone has had, as the lowest layer: corrections
     * only, so the prompt budget goes to the person's own words, as on desktop.
     */
    static WordLayer defaultsLayer() {
        WordLayer rows = layerFromRows(WordLayer.DEFAULTS, "builtin", Prefs.backgroundPhraseReplacements());
        return new WordLayer(WordLayer.DEFAULTS, "", new ArrayList<>(), rows.corrections, new HashSet<>(),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>());
    }

    /** This phone's own list, as a layer: every row kept, never rejected. */
    static WordLayer phoneLayer(List<PhraseReplacement> rows) {
        return layerFromRows(WordLayer.PHONE, "phone", rows);
    }

    /**
     * Each row is a word for its spelling (heard as its forms that differ from it,
     * for the prompt's hint) and, exactly as before, a correction from all its heard
     * forms to that spelling. The context becomes the word's gloss
     * (one line, at most 80 characters, for the prompt only; the row keeps it all).
     * Earlier rows rank higher among the corrections, as they always won before.
     */
    static WordLayer layerFromRows(String layer, String prefix, List<PhraseReplacement> rows) {
        List<WordLayer.Term> terms = new ArrayList<>();
        List<WordLayer.Correction> corrections = new ArrayList<>();
        // Rows that spell the same word share one word entry (its heard forms and first context).
        java.util.Map<String, Integer> termAt = new java.util.HashMap<>();
        int index = 0;
        for (PhraseReplacement row : rows) {
            String to = row.to == null ? "" : row.to.trim();
            index++;
            if (to.isEmpty()) {
                continue;
            }
            String key = WordLayer.norm(to);
            List<String> differing = new ArrayList<>();
            for (String form : row.heardForms()) {
                if (!WordLayer.norm(form).equals(key)) {
                    differing.add(form);
                }
            }
            String gloss = WordPrompt.promptWord(row.context);
            if (gloss.codePointCount(0, gloss.length()) > 80) {
                gloss = gloss.substring(0, gloss.offsetByCodePoints(0, 80));
            }
            Integer at = termAt.get(key);
            if (at == null) {
                termAt.put(key, terms.size());
                terms.add(new WordLayer.Term(prefix + "-" + index, to, differing, "", gloss,
                        WordLayer.PHONE.equals(layer) ? 100 : 50,
                        Collections.singletonList(WordLayer.EVERY_APP), layer));
            } else {
                WordLayer.Term first = terms.get(at);
                List<String> heard = new ArrayList<>(first.heardAs);
                for (String form : differing) {
                    if (!heard.contains(form)) {
                        heard.add(form);
                    }
                }
                terms.set(at, new WordLayer.Term(first.id, first.text, heard, first.kind,
                        first.gloss.isEmpty() ? gloss : first.gloss, first.priority, first.apps, layer));
            }
            // Every row stays a correction with all its heard forms, as before: a row
            // that only spells its own word still fixes its capitals and spacing
            // ("github" is written "GitHub").
            List<String> forms = row.heardForms();
            if (!forms.isEmpty()) {
                corrections.add(new WordLayer.Correction(prefix + "-fix-" + index, forms, to,
                        Collections.singletonList(WordLayer.EVERY_APP), layer));
            }
        }
        Collections.reverse(corrections);
        return new WordLayer(layer, "", terms, corrections, new HashSet<>(),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>());
    }

    // ---------------------------------------------------------------- what a dictation uses

    /** The vocabulary one dictation uses, fixed when it starts. */
    static final class DictationWords {
        /** True: the 0.8.1 behaviour (no synced copy yet). */
        final boolean legacy;
        final String prompt;
        final List<String> keywords;
        /** Corrections in force, in the order they are applied (the first wins an overlap). */
        final List<PhraseReplacement> corrections;
        final String snapshotId;
        /**
         * 0.8.4: the tidy-up's word list ({@link TidyWords}): every word the prompt chose,
         * with their notes (0.8.5: the request itself carries only the first
         * {@link WordPrompt#TRANSCRIBE_TERMS}). Empty in the 0.8.1 behaviour (no synced copy yet).
         */
        final TidyWords.Block tidyWords;

        DictationWords(boolean legacy, String prompt, List<String> keywords, List<PhraseReplacement> corrections,
                       String snapshotId) {
            this(legacy, prompt, keywords, corrections, snapshotId, TidyWords.Block.EMPTY);
        }

        DictationWords(boolean legacy, String prompt, List<String> keywords, List<PhraseReplacement> corrections,
                       String snapshotId, TidyWords.Block tidyWords) {
            this.legacy = legacy;
            this.prompt = prompt == null ? "" : prompt;
            this.keywords = Collections.unmodifiableList(new ArrayList<>(keywords));
            this.corrections = Collections.unmodifiableList(new ArrayList<>(corrections));
            this.snapshotId = snapshotId == null ? "" : snapshotId;
            this.tidyWords = tidyWords == null ? TidyWords.Block.EMPTY : tidyWords;
        }

        static DictationWords legacy(List<PhraseReplacement> all) {
            return new DictationWords(true, PersonalVocabulary.transcriptionPrompt(all),
                    PersonalVocabulary.keywords(all), all, "");
        }

        /** From a merged snapshot, for a dictation into {@code app} (an Android package, or ""). */
        static DictationWords fromSnapshot(WordMerge.Snapshot snapshot, String app) {
            WordPrompt.Built built = WordPrompt.build(snapshot, app);
            List<PhraseReplacement> corrections = new ArrayList<>();
            for (WordLayer.Correction c : snapshot.correctionsFor(app)) {
                corrections.add(new PhraseReplacement(String.join("\n", c.from), c.to));
            }
            return new DictationWords(false, built.prompt, WordPrompt.keywords(built.sent), corrections, snapshot.id,
                    TidyWords.forTerms(built.chosen, TidyWords.TIDY_WORDS_CHARS));
        }
    }

    /** What the Word lists screen and the status file show. */
    static final class View {
        /** Null while the phone dictates with its own list only. */
        final WordMerge.Snapshot snapshot;
        final State state;

        View(WordMerge.Snapshot snapshot, State state) {
            this.snapshot = snapshot;
            this.state = state;
        }
    }

    /**
     * For the moment a recording starts, on the keyboard's main thread: never reads
     * a file, never merges. It takes the snapshot already built (by the keyboard
     * opening, the last sync or an earlier dictation). When that is out of date it
     * takes the last one built for the same user and has a fresh one built in the
     * background; with none at all (a cold start) it dictates as 0.8.1 did.
     */
    static DictationWords forRecordStart(Context context, String app) {
        try {
            Enrolment enrolment = SyncStore.wordEnrolment(context);
            if (enrolment == null) {
                return DictationWords.legacy(Prefs.allPhraseReplacements(context));
            }
            String key = folderKey(enrolment);
            String rows = Prefs.userPhraseReplacementsJson(context);
            Cached hit = cached;
            View view;
            if (hit != null && hit.generation == generation && hit.key.equals(key) && hit.rows.equals(rows)) {
                view = hit.view;
            } else {
                buildInBackground(context);
                Cached last = lastBuilt;
                view = last != null && last.key.equals(key) ? last.view : null;
            }
            if (view == null || view.snapshot == null) {
                return DictationWords.legacy(Prefs.allPhraseReplacements(context));
            }
            // The same snapshot into the same app gives the same words: built once.
            String memoKey = view.snapshot.id + "\n" + (app == null ? "" : app);
            Object[] memo = lastWords;
            if (memo != null && memoKey.equals(memo[0]) && memo[1] == view.snapshot) {
                return (DictationWords) memo[2];
            }
            DictationWords words = DictationWords.fromSnapshot(view.snapshot, app);
            lastWords = new Object[]{memoKey, view.snapshot, words};
            return words;
        } catch (RuntimeException | LinkageError e) {
            return DictationWords.legacy(Prefs.allPhraseReplacements(context));
        }
    }

    /** The last words built at a record start: {key, snapshot, words}. */
    private static volatile Object[] lastWords;

    private static final java.util.concurrent.ExecutorService BUILDER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "VoiceFlowWordLists");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.concurrent.atomic.AtomicBoolean BUILDING = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * Where background builds are handed: {@link #BUILDER}. A test may wrap it to
     * hold a build back until it has checked what the caller did without one.
     */
    static volatile java.util.concurrent.Executor builds = BUILDER;

    /** Builds the snapshot off the main thread; one build at a time. */
    static void buildInBackground(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!BUILDING.compareAndSet(false, true)) {
            return;
        }
        try {
            builds.execute(() -> {
                try {
                    prewarm(app);
                } finally {
                    BUILDING.set(false);
                }
            });
        } catch (RuntimeException e) {
            BUILDING.set(false);
        }
    }

    /** Waits for a background build (tests). */
    static void awaitBackgroundBuild() throws InterruptedException {
        java.util.concurrent.Future<?> done = BUILDER.submit(() -> { });
        try {
            done.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The same, off the main thread (a transcription retry, a test): builds the
     * snapshot itself when it has to. Never touches the network.
     */
    static DictationWords forDictation(Context context, String app) {
        try {
            View view = current(context);
            if (view.snapshot == null) {
                return DictationWords.legacy(Prefs.allPhraseReplacements(context));
            }
            return DictationWords.fromSnapshot(view.snapshot, app);
        } catch (RuntimeException | LinkageError e) {
            // Dictation must never fail because of the word lists.
            return DictationWords.legacy(Prefs.allPhraseReplacements(context));
        }
    }

    /** Loads the offline copy ahead of the first dictation (keyboard opening). */
    static void prewarm(Context context) {
        try {
            current(context);
        } catch (RuntimeException | LinkageError ignored) {
            // The next dictation loads it, or uses the phone's own list.
        }
    }

    /** The phone's list changed, or a sync stored something new. */
    static void invalidate() {
        synchronized (LOCK) {
            generation++;
            cached = null;
        }
    }

    private static final class Cached {
        final int generation;
        final String key;
        final String rows;
        final View view;

        Cached(int generation, String key, String rows, View view) {
            this.generation = generation;
            this.key = key;
            this.rows = rows;
            this.view = view;
        }
    }

    static View current(Context context) {
        Enrolment enrolment = SyncStore.wordEnrolment(context);
        String key = enrolment == null ? "" : folderKey(enrolment);
        String rows = Prefs.userPhraseReplacementsJson(context);
        Cached hit = cached;
        if (hit != null && hit.generation == generation && hit.key.equals(key) && hit.rows.equals(rows)) {
            return hit.view;
        }
        int gen = generation;
        View view = load(context, enrolment, rows);
        synchronized (LOCK) {
            Cached built = new Cached(gen, key, rows, view);
            lastBuilt = built;
            if (gen == generation) {
                cached = built;
            }
        }
        return view;
    }

    private static View load(Context context, Enrolment enrolment, String rowsJson) {
        if (enrolment == null) {
            return new View(null, State.empty());
        }
        File dir = folder(context, enrolment);
        State state = State.read(dir);
        if (!state.accepted || !enrolment.user.equals(state.user)) {
            return new View(null, state);
        }
        List<WordLayer> layers = new ArrayList<>();
        layers.add(defaultsLayer());
        for (String layer : WordLayer.FILE_LAYERS) {
            byte[] bytes = readFile(new File(dir, "layer-" + layer + ".json"), WordLayer.MAX_LAYER_BYTES);
            if (bytes == null) {
                continue;
            }
            try {
                layers.add(WordLayer.parse(bytes, layer, enrolment.user));
            } catch (WordLayer.Rejected e) {
                // Only validated files are kept, so this is a damaged copy: left out.
            }
        }
        layers.add(phoneLayer(Prefs.parsePhraseReplacements(rowsJson)));
        List<WordOp> ops = new ArrayList<>();
        File[] files = new File(dir, "ops").listFiles();
        if (files != null) {
            for (File file : files) {
                if (!OP_NAME.matcher(file.getName()).matches()) {
                    continue;
                }
                WordOp op = WordOp.parse(readFile(file, WordOp.MAX_OP_BYTES));
                if (op != null) {
                    ops.add(op);
                }
            }
        }
        return new View(WordMerge.merge(layers, ops, BuiltinStyles.ids()), state);
    }

    // ---------------------------------------------------------------- the offline copy

    /** The sync state kept with the offline copy. Counts and codes only. */
    static final class State {
        String user = "";
        boolean accepted;
        String manifestEtag = "";
        long lastSyncAt;
        long lastAttemptAt;
        boolean reachable = true;
        final JSONObject layers;
        final Set<String> badOps;
        final List<String> errors;

        State(JSONObject layers, Set<String> badOps, List<String> errors) {
            this.layers = layers;
            this.badOps = badOps;
            this.errors = errors;
        }

        static State empty() {
            return new State(new JSONObject(), new LinkedHashSet<>(), new ArrayList<>());
        }

        JSONObject layer(String name) {
            JSONObject entry = layers.optJSONObject(name);
            if (entry == null) {
                entry = new JSONObject();
                try {
                    layers.put(name, entry);
                } catch (JSONException ignored) {
                    // A fixed key; cannot fail.
                }
            }
            return entry;
        }

        static State read(File dir) {
            byte[] bytes = readFile(new File(dir, "state.json"), 256 * 1024);
            if (bytes == null) {
                return empty();
            }
            try {
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                Set<String> bad = new LinkedHashSet<>();
                JSONArray badArray = json.optJSONArray("badOps");
                for (int i = 0; badArray != null && i < badArray.length(); i++) {
                    bad.add(badArray.optString(i));
                }
                List<String> errors = new ArrayList<>();
                JSONArray errorArray = json.optJSONArray("errors");
                for (int i = 0; errorArray != null && i < errorArray.length(); i++) {
                    errors.add(errorArray.optString(i));
                }
                JSONObject layers = json.optJSONObject("layers");
                State state = new State(layers == null ? new JSONObject() : layers, bad, errors);
                state.user = json.optString("user", "");
                state.accepted = json.optBoolean("accepted", false);
                state.manifestEtag = json.optString("manifestEtag", "");
                state.lastSyncAt = json.optLong("lastSyncAt", 0L);
                state.lastAttemptAt = json.optLong("lastAttemptAt", 0L);
                state.reachable = json.optBoolean("reachable", true);
                return state;
            } catch (JSONException e) {
                return empty();
            }
        }

        void write(File dir) throws IOException {
            try {
                JSONObject json = new JSONObject()
                        .put("user", user)
                        .put("accepted", accepted)
                        .put("manifestEtag", manifestEtag)
                        .put("lastSyncAt", lastSyncAt)
                        .put("lastAttemptAt", lastAttemptAt)
                        .put("reachable", reachable)
                        .put("layers", layers)
                        .put("badOps", new JSONArray(badOps))
                        .put("errors", new JSONArray(errors));
                writeAtomically(new File(dir, "state.json"), json.toString().getBytes(StandardCharsets.UTF_8));
            } catch (JSONException e) {
                throw new IOException("state-unbuildable");
            }
        }
    }

    /** One folder per gateway and user, named by a hash of both. */
    static String folderKey(Enrolment enrolment) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((enrolment.gateway + "\n" + enrolment.user).getBytes(StandardCharsets.UTF_8));
            return GatewayClient.hex(hash).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("no sha-256");
        }
    }

    static File root(Context context) {
        return new File(SyncStore.syncDir(context), "words");
    }

    static File folder(Context context, Enrolment enrolment) {
        return new File(root(context), folderKey(enrolment));
    }

    /** Removes every offline copy that does not belong to {@code keep} (null: removes all). */
    static void removeOtherCopies(Context context, Enrolment keep) {
        File[] dirs = root(context).listFiles();
        if (dirs == null) {
            return;
        }
        String keepKey = keep == null ? null : folderKey(keep);
        boolean removed = false;
        for (File dir : dirs) {
            if (!dir.getName().equals(keepKey)) {
                removeTree(dir);
                removed = true;
            }
        }
        if (removed) {
            // Nothing built from the removed copy stays in memory either.
            lastBuilt = null;
            lastWords = null;
            invalidate();
        }
    }

    private static void removeTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                removeTree(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }

    // ---------------------------------------------------------------- one sync

    /** What one refresh did, for the status file. */
    enum Outcome {
        UPDATED, UNCHANGED, OFFLINE, REFUSED, SKIPPED
    }

    /** Thrown when the gateway refused us (401/403): the caller counts it like any refused request. */
    static final class AuthRefused extends IOException {
        final GatewayClient.GatewayException cause;

        AuthRefused(GatewayClient.GatewayException cause) {
            super(cause.code);
            this.cause = cause;
        }
    }

    /**
     * One refresh from the gateway, inside the sync worker's single run. Reads the
     * manifest (a bodiless 304 when nothing changed), fetches only layers whose
     * hash changed and edits never seen, validates each, and keeps the last good
     * copy of anything bad or missing. Never throws for content problems; throws
     * {@link AuthRefused} for a 401/403 so the worker can count it, after the
     * state is saved.
     */
    static Outcome refresh(Context context, WordSource client, Enrolment enrolment) throws AuthRefused {
        removeOtherCopies(context, enrolment);
        File dir = folder(context, enrolment);
        File opsDir = new File(dir, "ops");
        if (!opsDir.isDirectory() && !opsDir.mkdirs()) {
            return Outcome.SKIPPED;
        }
        State state = State.read(dir);
        if (!enrolment.user.equals(state.user)) {
            state = State.empty();
            state.user = enrolment.user;
        }
        long now = System.currentTimeMillis();
        state.lastAttemptAt = now;
        state.errors.clear();
        AuthRefused refused = null;
        Outcome outcome;
        try {
            outcome = fetchAll(client, enrolment, dir, opsDir, state);
            state.reachable = true;
            state.lastSyncAt = now;
        } catch (GatewayClient.GatewayException e) {
            state.reachable = true;
            // A GitHub refusal keeps its own code ("github-401"); the gateway's are "gateway-...".
            state.errors.add(e.code.startsWith("github-") ? e.code : "gateway-" + e.code);
            outcome = Outcome.REFUSED;
            if (UpdateEvidence.ERROR_AUTH.equals(e.kind)) {
                refused = new AuthRefused(e);
            }
        } catch (IOException e) {
            state.reachable = false;
            outcome = Outcome.OFFLINE;
        }
        // The enrolment may have changed while this ran: never keep another user's copy.
        Enrolment after = SyncStore.wordEnrolment(context);
        if (after == null || !folderKey(after).equals(folderKey(enrolment))) {
            removeOtherCopies(context, after);
            return Outcome.SKIPPED;
        }
        try {
            state.write(dir);
        } catch (IOException e) {
            // The next run tries again; the copies already written stay valid.
        }
        invalidate();
        // Built here, on the sync worker, so the next recording finds it ready.
        prewarm(context);
        if (refused != null) {
            throw refused;
        }
        return outcome;
    }

    private static Outcome fetchAll(WordSource client, Enrolment enrolment, File dir, File opsDir, State state)
            throws IOException {
        GatewayClient.Fetched fetched = client.manifest(state.accepted ? state.manifestEtag : "");
        byte[] manifestBytes = fetched.notModified ? readFile(new File(dir, "manifest.json"), GatewayClient.MAX_MANIFEST_BYTES)
                : fetched.body;
        if (manifestBytes == null) {
            // A 304 with no kept manifest: ask again in full.
            fetched = client.manifest("");
            manifestBytes = fetched.body;
        }
        JSONObject manifest;
        try {
            manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            state.errors.add("manifest-unreadable");
            return Outcome.UNCHANGED;
        }
        if (!"vf-sync-manifest".equals(manifest.opt("schema"))
                || !enrolment.user.equals(manifest.opt("user"))
                || !enrolment.device.equals(manifest.opt("device"))) {
            state.errors.add("manifest-mismatch");
            return Outcome.UNCHANGED;
        }
        boolean changed = false;
        if (!fetched.notModified) {
            writeAtomically(new File(dir, "manifest.json"), manifestBytes);
        }
        JSONObject layers = manifest.optJSONObject("layers");
        for (String name : WordLayer.FILE_LAYERS) {
            changed |= refreshLayer(client, enrolment, dir, state, name, layers == null ? null : layers.opt(name));
        }
        JSONArray names = manifest.optJSONArray("ops");
        int fetchedOps = 0;
        boolean more = false;
        if (names != null) {
            for (int i = 0; i < names.length() && i < MAX_OPS_KEPT; i++) {
                String name = names.optString(i, "");
                if (!OP_NAME.matcher(name).matches() || state.badOps.contains(name)) {
                    continue;
                }
                File target = new File(opsDir, name);
                if (target.isFile()) {
                    continue;
                }
                if (fetchedOps >= MAX_OPS_PER_RUN) {
                    more = true;
                    break;
                }
                fetchedOps++;
                byte[] bytes;
                try {
                    bytes = client.op(name).body;
                } catch (GatewayClient.GatewayException e) {
                    if (UpdateEvidence.ERROR_AUTH.equals(e.kind)) {
                        throw e;
                    }
                    if (e.status == 429 || e.status >= 500) {
                        more = true; // busy: the rest wait for the next run
                        break;
                    }
                    continue; // gone, or too large: tried again next time
                }
                WordOp op = WordOp.parse(bytes);
                if (op == null || !(op.id + ".json").equals(name)) {
                    state.badOps.add(name);
                    continue;
                }
                writeAtomically(target, bytes);
                changed = true;
            }
        }
        if (more) {
            // Ask in full next time, so the rest are listed again even if nothing else changes.
            state.manifestEtag = "";
        } else {
            state.manifestEtag = fetched.etag == null ? "" : fetched.etag;
        }
        if (!state.accepted) {
            state.accepted = true;
            changed = true;
        }
        return changed ? Outcome.UPDATED : Outcome.UNCHANGED;
    }

    /** True when the kept copy changed. */
    private static boolean refreshLayer(WordSource client, Enrolment enrolment, File dir, State state, String name,
                                        Object entry) throws IOException {
        JSONObject layer = state.layer(name);
        File file = new File(dir, "layer-" + name + ".json");
        boolean have = file.isFile();
        try {
            if (!(entry instanceof JSONObject)) {
                // Not on the sync server. A copy read before stays until a valid file comes back.
                layer.put("state", have ? "cached" : "missing");
                layer.put("error", have ? "missing-on-server" : "");
                return false;
            }
            JSONObject info = (JSONObject) entry;
            // The gateway names a file by its SHA-256, GitHub by its blob id (WordSource#revision).
            String revision = WordSource.revision(info);
            if (revision.isEmpty() || info.optLong("size", 0L) > WordLayer.MAX_LAYER_BYTES) {
                layer.put("state", have ? "cached" : "missing");
                layer.put("error", "too-large");
                return false;
            }
            if (have && revision.equals(kept(layer, "revision", "sha256"))) {
                layer.put("state", "ok");
                layer.put("error", "");
                return false;
            }
            if (revision.equals(kept(layer, "rejectedRevision", "rejectedSha"))) {
                layer.put("state", have ? "cached" : "missing");
                return false; // the same bad file again: its error stays
            }
            byte[] bytes;
            try {
                bytes = client.layer(name).body;
            } catch (GatewayClient.GatewayException e) {
                if (UpdateEvidence.ERROR_AUTH.equals(e.kind)) {
                    throw e;
                }
                layer.put("state", have ? "cached" : "missing");
                layer.put("error", e.status == 404 ? "missing-on-server" : "http-" + e.status);
                return false;
            }
            String got = client.revisionOf(bytes);
            WordLayer parsed;
            try {
                parsed = WordLayer.parse(bytes, name, enrolment.user);
            } catch (WordLayer.Rejected rejected) {
                layer.put("state", have ? "cached" : "missing");
                layer.put("error", "rejected-" + rejected.reason);
                layer.put("rejectedRevision", got);
                layer.remove("rejectedSha");
                state.errors.add(name + ": rejected (" + rejected.reason + ")");
                return false;
            }
            writeAtomically(file, bytes);
            layer.put("revision", got);
            layer.remove("sha256");
            layer.put("rejectedRevision", "");
            layer.remove("rejectedSha");
            layer.put("state", "ok");
            layer.put("error", "");
            // Only a real timestamp reaches the status file (no text from the list, nothing long).
            layer.put("updatedAt", parsed.updatedAt.length() <= 40 && WordOp.instant(parsed.updatedAt) != null ? parsed.updatedAt : "");
            layer.put("words", parsed.terms.size());
            layer.put("corrections", parsed.corrections.size());
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * A kept revision: the new field, or a value kept before revisions had a kind
     * (a bare SHA-256 from the gateway), read as "sha256:..." so nothing is fetched again.
     */
    static String kept(JSONObject layer, String key, String legacyKey) {
        String value = layer.optString(key, "");
        if (!value.isEmpty()) {
            return value;
        }
        String legacy = layer.optString(legacyKey, "");
        return legacy.isEmpty() ? "" : "sha256:" + legacy;
    }

    // ---------------------------------------------------------------- status

    /** The {@code profile} block of the status file: counts and codes, never a word. */
    static JSONObject statusBlock(Context context) throws JSONException {
        View view = current(context);
        State state = view.state;
        JSONObject profile = new JSONObject();
        WordMerge.Snapshot snapshot = view.snapshot;
        if (snapshot != null) {
            // Styles reach the phone in a later release.
            profile.put("snapshotId", snapshot.id)
                    .put("words", snapshot.terms.size())
                    .put("corrections", snapshot.corrections.size())
                    .put("styles", 0);
        }
        profile.put("lastSyncAt", state.lastSyncAt > 0 ? StatusReport.iso(state.lastSyncAt) : JSONObject.NULL)
                .put("sourceReachable", state.reachable)
                .put("usingCache", !state.reachable)
                .put("pendingEdits", 0);
        JSONArray layers = new JSONArray();
        for (String name : WordLayer.FILE_LAYERS) {
            JSONObject kept = state.layers.optJSONObject(name);
            if (kept == null) {
                continue;
            }
            JSONObject out = new JSONObject().put("layer", name);
            String layerState = kept.optString("state", "missing");
            if (!state.reachable && !"missing".equals(layerState)) {
                layerState = "offline";
            }
            out.put("state", layerState);
            if (!kept.optString("updatedAt", "").isEmpty()) {
                out.put("updatedAt", kept.optString("updatedAt"));
            }
            if (!kept.optString("error", "").isEmpty()) {
                out.put("error", kept.optString("error"));
            }
            // As the desktop reports them: what the kept file holds.
            if (kept.has("words")) {
                out.put("words", kept.optInt("words")).put("corrections", kept.optInt("corrections"));
            }
            layers.put(out);
        }
        profile.put("layers", layers);
        profile.put("issues", new JSONArray(snapshot == null ? Collections.emptyList() : snapshot.issues));
        profile.put("errors", new JSONArray(state.errors));
        return profile;
    }

    // ---------------------------------------------------------------- files

    static byte[] readFile(File file, int max) {
        if (!file.isFile() || file.length() > max) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            return GatewayClient.readCapped(in, max);
        } catch (IOException e) {
            return null;
        }
    }

    static void writeAtomically(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            if (!target.delete() || !tmp.renameTo(target)) {
                throw new IOException("could not keep " + target.getName());
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return GatewayClient.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("no sha-256");
        }
    }

    /** For the word lists screen: lower-case labels per layer. */
    static String badge(String layer) {
        switch (layer) {
            case WordLayer.GENERATED:
                return "From your notes";
            case WordLayer.HOUSEHOLD:
                return "Household";
            case WordLayer.PERSONAL:
            case WordLayer.EDIT:
                return "Your list";
            case WordLayer.DEFAULTS:
                return "Built in";
            default:
                return "This phone";
        }
    }

    static List<String> layerOrder() {
        return Arrays.asList(WordLayer.DEFAULTS, WordLayer.HOUSEHOLD, WordLayer.GENERATED, WordLayer.PERSONAL,
                WordLayer.PHONE, WordLayer.EDIT);
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
