package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * A manual end-to-end run of the word-list sync against a real vf-sync gateway
 * with a test token. Skipped unless VF_E2E_WORDS_DIR is set (never in CI). The
 * folder holds {@code gateway.txt}, {@code token.txt}, {@code device.txt},
 * {@code user.txt}, optionally {@code words.txt} (words to look for in the
 * prompt, one per line) and {@code mode.txt} ("sync", or "revoked" for a run
 * after the token was revoked). A summary goes to {@code words-e2e.json}:
 * counts, and whether each listed word reached the prompt; the token is never
 * written anywhere.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LiveWordListsEndToEndTest {
    private static String read(File dir, String name) throws Exception {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8).trim();
    }

    @Test
    public void fetchMergeAndReport() throws Exception {
        String path = System.getenv("VF_E2E_WORDS_DIR");
        assumeTrue(path != null && !path.isEmpty());
        File dir = new File(path);
        String origin = Enrolment.normalizeGateway(read(dir, "gateway.txt"));
        assertNotNull(origin);
        String token = read(dir, "token.txt");
        String user = read(dir, "user.txt");
        String device = read(dir, "device.txt");
        String mode = new File(dir, "mode.txt").isFile() ? read(dir, "mode.txt") : "sync";
        Context context = RuntimeEnvironment.getApplication();

        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        SyncStore.keys = () -> key;
        SyncStore.prefs(context).edit().clear().putString("device_id", device).commit();
        Enrolment enrolment = SyncStore.enrol(context, user, origin, token);
        assertEquals(device, enrolment.device);
        GatewayClient client = new GatewayClient(origin, token);
        JSONObject summary = new JSONObject();

        if ("revoked".equals(mode)) {
            // A copy as a phone would have kept it, then a run with the revoked token.
            File keep = new File(dir, "kept-copy");
            copyTree(keep, WordLists.folder(context, enrolment));
            WordLists.invalidate();
            int before = WordLists.current(context).snapshot.terms.size();
            try {
                WordLists.refresh(context, client, enrolment);
                fail("a revoked token must be refused");
            } catch (WordLists.AuthRefused e) {
                summary.put("refusedStatus", e.cause.status).put("refusedKind", e.cause.kind);
            }
            WordLists.View after = WordLists.current(context);
            assertNotNull("the copy stays after a refusal", after.snapshot);
            assertEquals(before, after.snapshot.terms.size());
            summary.put("wordsKeptAfterRefusal", after.snapshot.terms.size());
            write(dir, summary);
            return;
        }

        long start = System.currentTimeMillis();
        WordLists.Outcome outcome = WordLists.refresh(context, client, enrolment);
        summary.put("firstSync", outcome.name()).put("firstSyncMs", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        summary.put("secondSync", WordLists.refresh(context, client, enrolment).name())
                .put("secondSyncMs", System.currentTimeMillis() - start);

        // The sync server lists alone, merged as the desktop merges them (no built-ins, no phone rows).
        File folder = WordLists.folder(context, enrolment);
        List<WordLayer> layers = new ArrayList<>();
        JSONObject perLayer = new JSONObject();
        for (String name : WordLayer.FILE_LAYERS) {
            File file = new File(folder, "layer-" + name + ".json");
            if (file.isFile()) {
                WordLayer layer = WordLayer.parse(Files.readAllBytes(file.toPath()), name, user);
                layers.add(layer);
            }
        }
        List<WordOp> ops = new ArrayList<>();
        File[] opFiles = new File(folder, "ops").listFiles();
        for (File f : opFiles == null ? new File[0] : opFiles) {
            WordOp op = WordOp.parse(Files.readAllBytes(f.toPath()));
            if (op != null) {
                ops.add(op);
            }
        }
        WordMerge.Snapshot nas = WordMerge.merge(layers, ops, BuiltinStyles.ids());
        for (String name : WordLayer.FILE_LAYERS) {
            int corrections = 0;
            for (WordLayer.Correction c : nas.corrections) {
                if (c.layer.equals(name)) {
                    corrections++;
                }
            }
            perLayer.put(name, new JSONObject().put("words", nas.count(name)).put("corrections", corrections));
        }
        // For a comparison with the desktop's prompt builder (operator's scratch only).
        JSONArray nasTerms = new JSONArray();
        for (WordLayer.Term t : nas.terms) {
            nasTerms.put(new JSONObject().put("id", t.id).put("text", t.text).put("heardAs", new JSONArray(t.heardAs))
                    .put("gloss", t.gloss).put("priority", t.priority).put("apps", new JSONArray(t.apps))
                    .put("layer", WordLayer.EDIT.equals(t.layer) ? "edit" : t.layer));
        }
        WordPrompt.Built nasPrompt = WordPrompt.build(nas, null);
        try (FileOutputStream out = new FileOutputStream(new File(dir, "nas-terms.json"))) {
            out.write(new JSONObject().put("terms", nasTerms).put("promptChars", nas.promptChars())
                    .put("promptTerms", nas.promptTerms()).put("prompt", nasPrompt.prompt)
                    .toString().getBytes(StandardCharsets.UTF_8));
        }
        summary.put("nasWords", nas.terms.size()).put("nasCorrections", nas.corrections.size())
                .put("nasIssues", new JSONArray(nas.issues)).put("opsKept", ops.size()).put("perLayer", perLayer);

        // What the phone uses: built-ins + NAS + this (empty) phone list.
        WordLists.View view = WordLists.current(context);
        assertNotNull(view.snapshot);
        summary.put("phoneWords", view.snapshot.terms.size()).put("phoneCorrections", view.snapshot.corrections.size());
        WordLists.DictationWords words = WordLists.forDictation(context, "com.google.android.apps.messaging");
        assertFalse(words.legacy);
        summary.put("promptChars", words.prompt.length()).put("keywords", words.keywords.size());
        if (new File(dir, "words.txt").isFile()) {
            JSONObject found = new JSONObject();
            for (String word : read(dir, "words.txt").split("\\r?\\n")) {
                if (word.trim().isEmpty()) {
                    continue;
                }
                boolean inKeywords = words.keywords.contains(word.trim());
                int at = words.prompt.indexOf(word.trim() + " (");
                String hint = at < 0 ? "" : words.prompt.substring(at, Math.min(words.prompt.length(),
                        words.prompt.indexOf(')', at) + 1));
                found.put(word.trim(), new JSONObject().put("keyword", inKeywords).put("hint", hint));
            }
            summary.put("words", found);
            // The private result: the whole prompt, for the operator to read (scratch only).
            try (FileOutputStream out = new FileOutputStream(new File(dir, "prompt.txt"))) {
                out.write(words.prompt.getBytes(StandardCharsets.UTF_8));
            }
        }

        // The status file, as the phone sends it, must be accepted by the gateway.
        JSONObject profile = WordLists.statusBlock(context);
        client.putStatus(StatusReport.build(enrolment, "0.8.2", "Android (e2e from JVM)", System.currentTimeMillis(),
                UpdateEvidence.fresh(48, System.currentTimeMillis()), profile));
        summary.put("statusAccepted", true).put("statusProfile", profile);
        assertTrue(profile.getInt("words") > 0);

        // Keep the copy for the "revoked" run.
        copyTree(folder, new File(dir, "kept-copy"));
        write(dir, summary);
    }

    private static void write(File dir, JSONObject summary) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(dir, "words-e2e.json"))) {
            out.write(summary.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyTree(File from, File to) throws Exception {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) {
                throw new AssertionError("mkdir " + to);
            }
            File[] children = from.listFiles();
            for (File child : children == null ? new File[0] : children) {
                copyTree(child, new File(to, child.getName()));
            }
        } else {
            Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
