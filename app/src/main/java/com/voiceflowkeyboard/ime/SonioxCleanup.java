package com.voiceflowkeyboard.ime;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 0.9.3: deletes what a Soniox dictation left on Soniox's side, the transcription first and
 * then the uploaded file, every time. What can't be deleted now (a transcription still
 * running when its dictation was cancelled answers 409, or the network dropped) is kept by
 * id, never with any text or key, and retried before the next Soniox dictation. A 404 means
 * it is gone already. Ids older than 30 days are dropped: Soniox has removed them by then.
 */
final class SonioxCleanup {
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
    static final int MAX_PENDING = 100;
    private static final String FILE = "voiceflow_soniox_cleanup";
    private static final String KEY_PENDING = "pending";

    /** Where the pending ids live; a map in tests. */
    interface Store {
        String read();

        void write(String value);
    }

    /** The app's cleanups run here, one at a time, so a dictation never waits for a delete. */
    private static final Executor BACKGROUND = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "VoiceFlowSonioxCleanup");
        thread.setDaemon(true);
        return thread;
    });
    /** At most this many pending ids are tried before each dictation. */
    static final int RETRY_BATCH = 10;

    private final Store store;
    private final SonioxClient.Clock clock;
    /** Null: run in the caller's thread (tests). */
    private final Executor executor;

    SonioxCleanup(Store store, SonioxClient.Clock clock) {
        this(store, clock, null);
    }

    SonioxCleanup(Store store, SonioxClient.Clock clock, Executor executor) {
        this.store = store;
        this.clock = clock;
        this.executor = executor;
    }

    private void run(Runnable work) {
        if (executor == null) {
            work.run();
        } else {
            executor.execute(work);
        }
    }

    static SonioxCleanup of(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return new SonioxCleanup(new Store() {
            @Override
            public String read() {
                return prefs.getString(KEY_PENDING, "[]");
            }

            @Override
            public void write(String value) {
                prefs.edit().putString(KEY_PENDING, value).commit();
            }
        }, System::currentTimeMillis, BACKGROUND);
    }

    /** After a dictation: delete its transcription and file, keeping what fails for later. */
    void delete(SonioxClient.Transport http, String apiKey, String transcriptionId, String fileId,
                SonioxClient.Sleeper sleeper) {
        if (transcriptionId == null && fileId == null) {
            return;
        }
        if (executor != null) {
            // Recorded first, so an app killed mid-delete still retries them next time.
            remember(transcriptionId, fileId);
            run(() -> {
                forget(transcriptionId, fileId);
                deleteNow(http, apiKey, transcriptionId, fileId, sleeper);
            });
            return;
        }
        deleteNow(http, apiKey, transcriptionId, fileId, sleeper);
    }

    private void remember(String transcriptionId, String fileId) {
        synchronized (SonioxCleanup.class) {
            List<String> pending = pending();
            long now = clock.now();
            if (transcriptionId != null) {
                pending.add("t:" + transcriptionId + "@" + now);
            }
            if (fileId != null) {
                pending.add("f:" + fileId + "@" + now);
            }
            save(pending);
        }
    }

    private void forget(String transcriptionId, String fileId) {
        synchronized (SonioxCleanup.class) {
            List<String> kept = new ArrayList<>();
            for (String entry : pending()) {
                boolean mine = (transcriptionId != null && entry.startsWith("t:" + transcriptionId + "@"))
                        || (fileId != null && entry.startsWith("f:" + fileId + "@"));
                if (!mine) {
                    kept.add(entry);
                }
            }
            save(kept);
        }
    }

    private void deleteNow(SonioxClient.Transport http, String apiKey, String transcriptionId, String fileId,
                           SonioxClient.Sleeper sleeper) {
        boolean transcriptionGone = transcriptionId == null || remove(http, apiKey, "transcriptions", transcriptionId);
        boolean fileGone = fileId == null;
        if (!transcriptionGone && fileId != null) {
            // A transcription that is still running can't be deleted. Removing its file
            // makes it fail (file_not_found), and a failed one can be.
            fileGone = remove(http, apiKey, "files", fileId);
            for (int attempt = 0; attempt < 4 && !transcriptionGone; attempt++) {
                try {
                    sleeper.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                transcriptionGone = remove(http, apiKey, "transcriptions", transcriptionId);
            }
        }
        if (!fileGone) {
            fileGone = remove(http, apiKey, "files", fileId);
        }
        List<String> later = new ArrayList<>();
        if (!transcriptionGone) {
            later.add("t:" + transcriptionId);
        }
        if (!fileGone) {
            later.add("f:" + fileId);
        }
        if (!later.isEmpty()) {
            synchronized (SonioxCleanup.class) {
                List<String> pending = pending();
                long now = clock.now();
                for (String item : later) {
                    pending.add(item + "@" + now);
                }
                save(pending);
            }
        }
    }

    /** Before a dictation: try the pending ids once more (at most {@link #RETRY_BATCH}). */
    void retry(SonioxClient.Transport http, String apiKey) {
        run(() -> retryNow(http, apiKey));
    }

    private void retryNow(SonioxClient.Transport http, String apiKey) {
        synchronized (SonioxCleanup.class) {
            List<String> pending = pending();
            if (pending.isEmpty()) {
                return;
            }
            List<String> kept = new ArrayList<>();
            long now = clock.now();
            int tried = 0;
            for (String entry : pending) {
                if (tried >= RETRY_BATCH) {
                    kept.add(entry);
                    continue;
                }
                int at = entry.lastIndexOf('@');
                long added = parseLong(at < 0 ? "" : entry.substring(at + 1));
                String item = at < 0 ? entry : entry.substring(0, at);
                if (item.length() < 3 || now - added > KEEP_MS) {
                    continue;
                }
                String kind = item.startsWith("t:") ? "transcriptions" : item.startsWith("f:") ? "files" : "";
                tried++;
                if (kind.isEmpty() || remove(http, apiKey, kind, item.substring(2))) {
                    continue;
                }
                kept.add(entry);
            }
            save(kept);
        }
    }

    List<String> pending() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(store.read());
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        } catch (JSONException ignored) {
            // A damaged record starts empty.
        }
        return out;
    }

    private void save(List<String> pending) {
        JSONArray array = new JSONArray();
        int from = Math.max(0, pending.size() - MAX_PENDING);
        for (int i = from; i < pending.size(); i++) {
            array.put(pending.get(i));
        }
        store.write(array.toString());
    }

    /** True when it is gone: deleted now (2xx) or already (404). */
    private static boolean remove(SonioxClient.Transport http, String apiKey, String kind, String id) {
        try {
            SonioxClient.Reply reply = http.send("DELETE", SonioxClient.API + "/" + kind + "/" + id, apiKey, null, null);
            return reply.ok() || reply.status == 404;
        } catch (Exception e) {
            return false;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
