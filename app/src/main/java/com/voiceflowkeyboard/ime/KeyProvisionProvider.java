package com.voiceflowkeyboard.ime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 0.9.3: puts an API key on the phone over USB without it ever being on a command line.
 * The manifest guards it with {@code android.permission.DUMP}, which the adb shell holds and
 * ordinary apps can't, like {@link SyncCommandReceiver}.
 *
 * <pre>
 *   adb shell content write --uri content://com.voiceflowkeyboard.ime.keys/keys/soniox   (key on stdin)
 *   adb shell content query --uri content://com.voiceflowkeyboard.ime.keys/keys/soniox   (the result)
 * </pre>
 *
 * The write hands the shell one end of a pipe; the key is read from the other, trimmed,
 * checked and saved like a key typed on the API keys screen. Nothing is logged, and the
 * query answers only {@code provider}, {@code state} ({@code saved} or {@code refused: why})
 * and {@code length}: never the key or any part of it.
 */
public final class KeyProvisionProvider extends ContentProvider {
    static final String AUTHORITY = "com.voiceflowkeyboard.ime.keys";
    static final int MAX_BYTES = 4096;

    private static final Object LOCK = new Object();
    private static String lastProvider = "";
    private static String lastState = "none";
    private static int lastLength = 0;
    private static CountDownLatch pending = new CountDownLatch(0);

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The provider a {@code keys/<provider>} uri names, or "" for anything else. */
    static String providerOf(Uri uri) {
        List<String> segments = uri == null ? null : uri.getPathSegments();
        if (segments == null || segments.size() != 2 || !"keys".equals(segments.get(0))) {
            return "";
        }
        return Prefs.PROVIDER_SONIOX.equals(segments.get(1)) ? Prefs.PROVIDER_SONIOX : "";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String provider = providerOf(uri);
        if (provider.isEmpty() || mode == null || !mode.contains("w")) {
            throw new FileNotFoundException("write a key to content://" + AUTHORITY + "/keys/soniox");
        }
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException e) {
            throw new FileNotFoundException("no pipe");
        }
        CountDownLatch done = new CountDownLatch(1);
        synchronized (LOCK) {
            pending = done;
        }
        ParcelFileDescriptor readSide = pipe[0];
        Thread reader = new Thread(() -> {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readSide)) {
                record(provider, receive(provider, in));
            } catch (Exception e) {
                record(provider, new Result("refused: could not read", 0));
            } finally {
                done.countDown();
            }
        }, "VoiceFlowKeyProvision");
        reader.start();
        return pipe[1];
    }

    static final class Result {
        final String state;
        final int length;

        Result(String state, int length) {
            this.state = state;
            this.length = length;
        }
    }

    /** Reads the key, checks it and saves it. */
    Result receive(String provider, InputStream in) throws IOException {
        String key = readKey(in);
        String problem = problem(key);
        if (problem != null) {
            return new Result("refused: " + problem, key == null ? 0 : key.length());
        }
        boolean saved = Prefs.PROVIDER_SONIOX.equals(provider) && Prefs.setSonioxApiKey(getContext(), key);
        return saved ? new Result("saved", key.length()) : new Result("refused: not saved", key.length());
    }

    /** At most {@link #MAX_BYTES}; null when there was more. Trimmed (a pipe adds a newline). */
    static String readKey(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BYTES) {
                return null;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    /** Why a key can't be right, or null when it can. */
    static String problem(String key) {
        if (key == null) {
            return "too long";
        }
        if (key.isEmpty()) {
            return "empty";
        }
        if (key.length() < 16) {
            return "too short";
        }
        if (key.length() > 512) {
            return "too long";
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c <= ' ' || c > '~') {
                return "not one line of printable characters";
            }
        }
        return null;
    }

    private static void record(String provider, Result result) {
        synchronized (LOCK) {
            lastProvider = provider;
            lastState = result.state;
            lastLength = result.length;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String provider = providerOf(uri);
        if (provider.isEmpty()) {
            return null;
        }
        CountDownLatch wait;
        synchronized (LOCK) {
            wait = pending;
        }
        try {
            // A write that just closed may still be saving.
            wait.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"provider", "state", "length", "stored"});
        synchronized (LOCK) {
            boolean mine = provider.equals(lastProvider);
            int stored = Prefs.sonioxApiKey(getContext()).trim().length();
            cursor.addRow(new Object[]{provider, mine ? lastState : "none", mine ? lastLength : 0, stored});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("write the key with content write");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    /** Tests: forget the last result. */
    static void resetForTest() {
        synchronized (LOCK) {
            lastProvider = "";
            lastState = "none";
            lastLength = 0;
            pending = new CountDownLatch(0);
        }
    }
}
