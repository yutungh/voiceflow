package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;

/** The Soniox async flow against a scripted fake server: no network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SonioxClientTest {
    private static final String KEY = "test-key-not-real-0000";

    /** One request the fake saw. */
    static final class Seen {
        final String method;
        final String url;
        final String body;
        final String key;

        Seen(String method, String url, String body, String key) {
            this.method = method;
            this.url = url;
            this.body = body;
            this.key = key;
        }
    }

    /** Answers in order by method and path; DELETEs answer 200 unless told otherwise. */
    static final class Fake implements SonioxClient.Transport {
        final List<Seen> seen = new ArrayList<>();
        final Deque<String[]> script = new ArrayDeque<>();
        final Deque<Integer> transcriptionDeletes = new ArrayDeque<>();
        Runnable onStatus;

        Fake then(String methodAndPathPrefix, int status, String body) {
            script.add(new String[]{methodAndPathPrefix, String.valueOf(status), body});
            return this;
        }

        @Override
        public SonioxClient.Reply send(String method, String url, String apiKey, SonioxClient.Body body,
                                       SpeechCancel cancel) throws IOException {
            String text = "";
            if (body != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                body.writeTo(out);
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            seen.add(new Seen(method, url, text, apiKey));
            if ("DELETE".equals(method)) {
                if (url.contains("/transcriptions/") && !transcriptionDeletes.isEmpty()) {
                    return new SonioxClient.Reply(transcriptionDeletes.poll(), "");
                }
                return new SonioxClient.Reply(200, "");
            }
            if (onStatus != null && url.matches(".*/transcriptions/[^/]+$")) {
                onStatus.run();
            }
            String[] next = script.poll();
            if (next == null) {
                throw new IOException("unexpected " + method + " " + url);
            }
            String path = url.substring(SonioxClient.API.length());
            assertTrue("expected " + next[0] + ", got " + method + " " + path, (method + " " + path).startsWith(next[0]));
            return new SonioxClient.Reply(Integer.parseInt(next[1]), next[2]);
        }

        List<String> deletes() {
            List<String> out = new ArrayList<>();
            for (Seen s : seen) {
                if ("DELETE".equals(s.method)) {
                    out.add(s.url.substring(SonioxClient.API.length()));
                }
            }
            return out;
        }
    }

    private File audio;
    private final StringBuilder stored = new StringBuilder("[]");
    private SonioxCleanup cleanup;
    private long now;

    @Before
    public void setUp() throws Exception {
        audio = File.createTempFile("soniox-test", ".m4a");
        audio.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(audio)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        now = 1_000_000L;
        cleanup = new SonioxCleanup(new SonioxCleanup.Store() {
            @Override
            public String read() {
                return stored.toString();
            }

            @Override
            public void write(String value) {
                stored.setLength(0);
                stored.append(value);
            }
        }, () -> now);
    }

    private String run(Fake fake, DictationLanguage language, List<String> terms, SpeechCancel cancel) throws Exception {
        return SonioxClient.transcribe(fake, KEY, "", audio, "a.m4a", "audio/mp4", language, terms, cancel, cleanup,
                millis -> now += millis, () -> now, SonioxClient.TIMEOUT_MS);
    }

    private static Fake happy() {
        return new Fake()
                .then("POST /files", 201, "{\"id\":\"file-1\",\"filename\":\"a.m4a\",\"size\":4}")
                .then("POST /transcriptions", 201, "{\"id\":\"tr-1\",\"status\":\"queued\"}")
                .then("GET /transcriptions/tr-1", 200, "{\"id\":\"tr-1\",\"status\":\"processing\"}")
                .then("GET /transcriptions/tr-1", 200, "{\"id\":\"tr-1\",\"status\":\"completed\"}")
                .then("GET /transcriptions/tr-1/transcript", 200, "{\"id\":\"tr-1\",\"text\":\" 我今天下午要去 Costco。 \",\"tokens\":[]}");
    }

    @Test
    public void uploadsCreatesPollsFetchesThenDeletesBoth() throws Exception {
        Fake fake = happy();
        String text = run(fake, DictationLanguage.CHINESE, Arrays.asList("Costco", "oat milk"), new SpeechCancel());
        assertEquals("我今天下午要去 Costco。", text);

        Seen upload = fake.seen.get(0);
        assertEquals("POST", upload.method);
        assertTrue(upload.body.contains("name=\"file\"; filename=\"a.m4a\""));
        assertTrue(upload.body.contains("Content-Type: audio/mp4"));
        for (Seen s : fake.seen) {
            assertEquals("the key only travels as the bearer", KEY, s.key);
            assertFalse(s.body.contains(KEY));
        }

        JSONObject create = new JSONObject(fake.seen.get(1).body);
        assertEquals("stt-async-v6", create.getString("model"));
        assertEquals("file-1", create.getString("file_id"));
        assertEquals(new JSONArray(Arrays.asList("zh", "en")).toString(), create.getJSONArray("language_hints").toString());
        assertEquals(new JSONArray(Arrays.asList("Costco", "oat milk")).toString(),
                create.getJSONObject("context").getJSONArray("terms").toString());
        assertFalse("hints are never strict", create.has("language_hints_strict"));

        assertEquals(Arrays.asList("/transcriptions/tr-1", "/files/file-1"), fake.deletes());
        assertEquals("[]", stored.toString());
    }

    @Test
    public void englishKeyboardHintsEnglishOnlyAndNoTermsSendsNoContext() throws Exception {
        JSONObject body = SonioxClient.transcriptionRequest("f", "", DictationLanguage.AUTO, Collections.<String>emptyList());
        assertEquals("stt-async-v6", body.getString("model"));
        assertEquals("the picked model is the one sent", "stt-async-v7",
                SonioxClient.transcriptionRequest("f", "stt-async-v7", DictationLanguage.AUTO, null).getString("model"));
        assertEquals("[\"en\"]", body.getJSONArray("language_hints").toString());
        assertFalse(body.has("context"));
        assertArrayEquals(new String[]{"zh", "en"}, SonioxClient.languageHints(DictationLanguage.CHINESE));
    }

    @Test
    public void termsAreDistinctOneLineAndCappedWellUnderTheContextLimit() {
        List<String> many = new ArrayList<>();
        many.add("Oat milk");
        many.add("oat MILK");
        many.add("two\nlines");
        many.add("");
        for (int i = 0; i < 400; i++) {
            many.add("Term" + i + "-abcdefghij");
        }
        List<String> capped = SonioxClient.capTerms(many);
        assertEquals("Oat milk", capped.get(0));
        assertEquals("two lines", capped.get(1));
        assertTrue(capped.size() <= SonioxClient.MAX_TERMS);
        int chars = 0;
        for (String t : capped) {
            chars += t.length();
        }
        assertTrue(chars <= SonioxClient.MAX_TERMS_CHARS);
        // ~10,000 characters is Soniox's whole-context cap; the terms stay far below it.
        assertTrue(SonioxClient.MAX_TERMS_CHARS < 10_000 / 2);
    }

    @Test
    public void moreTermsThanOpenAiGetsAreSent() {
        WordLists.DictationWords words = new WordLists.DictationWords(false, "p", Arrays.asList("A1", "B2"),
                Collections.<PhraseReplacement>emptyList(), "s", TidyWords.Block.EMPTY,
                Arrays.asList("A1", "B2", "C3", "D4"));
        assertEquals(Arrays.asList("A1", "B2", "C3", "D4"), SonioxClient.termsFor(words));
        WordLists.DictationWords legacy = WordLists.DictationWords.legacy(Collections.<PhraseReplacement>emptyList());
        assertTrue(SonioxClient.termsFor(legacy).isEmpty());
    }

    @Test
    public void aFailedJobThrowsItsMessageAndStillDeletesBoth() throws Exception {
        Fake fake = new Fake()
                .then("POST /files", 201, "{\"id\":\"file-2\"}")
                .then("POST /transcriptions", 201, "{\"id\":\"tr-2\"}")
                .then("GET /transcriptions/tr-2", 200,
                        "{\"id\":\"tr-2\",\"status\":\"error\",\"error_type\":\"x\",\"error_message\":\"Audio could not be decoded.\"}");
        try {
            run(fake, DictationLanguage.AUTO, Collections.<String>emptyList(), null);
            fail();
        } catch (ProviderHttpException e) {
            assertTrue(e.getMessage().contains("Audio could not be decoded."));
            assertEquals(Prefs.PROVIDER_SONIOX, e.provider);
        }
        assertEquals(Arrays.asList("/transcriptions/tr-2", "/files/file-2"), fake.deletes());
    }

    @Test
    public void anHttpErrorNeverEchoesTheKey() throws Exception {
        Fake fake = new Fake().then("POST /files", 401, "{\"error_type\":\"unauthenticated\",\"message\":\"Invalid API key.\"}");
        try {
            run(fake, DictationLanguage.AUTO, Collections.<String>emptyList(), null);
            fail();
        } catch (ProviderHttpException e) {
            assertEquals(401, e.status);
            assertFalse(e.getMessage().contains(KEY));
        }
        assertTrue("nothing was created, so nothing to delete", fake.deletes().isEmpty());
    }

    @Test
    public void pollingIsBoundedByTheTimeout() throws Exception {
        Fake fake = new Fake()
                .then("POST /files", 201, "{\"id\":\"file-3\"}")
                .then("POST /transcriptions", 201, "{\"id\":\"tr-3\"}");
        for (int i = 0; i < 500; i++) {
            fake.then("GET /transcriptions/tr-3", 200, "{\"status\":\"processing\"}");
        }
        fake.transcriptionDeletes.add(409); // still running: can't be deleted yet
        fake.transcriptionDeletes.add(200); // after its file went, it failed and can be
        long started = now;
        try {
            run(fake, DictationLanguage.AUTO, Collections.<String>emptyList(), null);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("too long"));
        }
        assertTrue(now - started <= SonioxClient.TIMEOUT_MS + 5_000);
        assertEquals(Arrays.asList("/transcriptions/tr-3", "/files/file-3", "/transcriptions/tr-3"), fake.deletes());
        assertEquals("[]", stored.toString());
    }

    @Test
    public void aCancelStopsThePollAndWhatCantBeDeletedIsRetriedLater() throws Exception {
        SpeechCancel cancel = new SpeechCancel();
        Fake fake = new Fake()
                .then("POST /files", 201, "{\"id\":\"file-4\"}")
                .then("POST /transcriptions", 201, "{\"id\":\"tr-4\"}")
                .then("GET /transcriptions/tr-4", 200, "{\"status\":\"processing\"}");
        fake.onStatus = cancel::cancel;
        for (int i = 0; i < 5; i++) {
            fake.transcriptionDeletes.add(409);
        }
        try {
            run(fake, DictationLanguage.AUTO, Collections.<String>emptyList(), cancel);
            fail();
        } catch (CancellationException expected) {
            // the poll stopped at once
        }
        long statusPolls = fake.seen.stream().filter(s -> "GET".equals(s.method)).count();
        assertEquals(1, statusPolls);
        List<String> pending = cleanup.pending();
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).startsWith("t:tr-4@"));

        Fake later = new Fake();
        cleanup.retry(later, KEY);
        assertEquals(Collections.singletonList("/transcriptions/tr-4"), later.deletes());
        assertEquals("[]", stored.toString());
    }

    @Test
    public void staleCleanupIdsAreDroppedAfterThirtyDays() throws Exception {
        stored.setLength(0);
        stored.append("[\"t:old@1\",\"f:new@" + now + "\"]");
        now += SonioxCleanup.KEEP_MS;
        Fake later = new Fake();
        later.transcriptionDeletes.add(500);
        cleanup.retry(later, KEY);
        assertEquals(Collections.singletonList("/files/new"), later.deletes());
        assertEquals("[]", stored.toString());
    }

    @Test
    public void anAnswerArrivingAfterTheDeadlineIsTooLate() throws Exception {
        Fake fake = new Fake()
                .then("POST /files", 201, "{\"id\":\"file-5\"}")
                .then("POST /transcriptions", 201, "{\"id\":\"tr-5\"}")
                .then("GET /transcriptions/tr-5", 200, "{\"status\":\"completed\"}");
        fake.onStatus = () -> now += SonioxClient.TIMEOUT_MS + 1; // a status call that hung past the deadline
        try {
            run(fake, DictationLanguage.AUTO, Collections.<String>emptyList(), null);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("too long"));
        }
        assertEquals(Arrays.asList("/transcriptions/tr-5", "/files/file-5"), fake.deletes());
    }

    @Test
    public void aTranscriptWithoutTextIsAnError() {
        try {
            SonioxClient.parseTranscript("{\"id\":\"x\",\"text\":\"  \"}");
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("did not include text"));
        }
    }

    @Test
    public void pollStartsFastThenSlowsDown() {
        assertEquals(250L, SonioxClient.pollDelay(0));
        assertEquals(500L, SonioxClient.pollDelay(8));
        assertEquals(1000L, SonioxClient.pollDelay(60));
    }
}
