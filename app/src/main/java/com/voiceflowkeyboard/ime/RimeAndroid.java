package com.voiceflowkeyboard.ime;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone's side of the Rime engine (0.9.0): one {@link RimeEngine} per
 * process, the native library, the data copy, Android's exit records for the
 * breaker, and the {@code pinyin} block of the status file.
 */
final class RimeAndroid {

    private RimeAndroid() {
    }

    private static final String PREFS = "vf_rime";
    private static final String KEY_LAST_START = "rime_last_start";
    private static final String KEY_LAST_START_AT = "rime_last_start_at";

    private static RimeEngine engine;

    /** This process's engine, created on first use. */
    static synchronized RimeEngine engine(Context context) {
        if (engine == null) {
            Context app = context.getApplicationContext();
            ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "vf-rime");
                thread.setDaemon(true);
                return thread;
            });
            engine = new RimeEngine(new RimeNative(), new Phone(app), breaker(app), worker);
        }
        return engine;
    }

    static RimeBreaker breaker(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new RimeBreaker(new RimeBreaker.Store() {
            @Override
            public String get(String key) {
                return prefs.getString(key, "");
            }

            @Override
            public void put(String key, String value) {
                // commit(): the markers must be on disk before the native call they bracket.
                prefs.edit().putString(key, value).commit();
            }
        });
    }

    /** Where Rime's data and what it learned live. */
    static File base(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), "rime");
    }

    static File userDir(Context context) {
        return new File(base(context), "user");
    }

    /** The {@code pinyin} block of the status file: engine choice and Rime's health. Counts and codes only. */
    static JSONObject statusBlock(Context context) throws JSONException {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RimeBreaker breaker = breaker(context);
        long now = System.currentTimeMillis();
        JSONObject breakerBlock = new JSONObject()
                .put("tripped", breaker.tripped())
                .put("reason", breaker.tripped() ? breaker.trippedReason() : JSONObject.NULL)
                .put("at", breaker.tripped() ? StatusReport.iso(breaker.trippedAt()) : JSONObject.NULL);
        JSONArray recent = new JSONArray();
        for (String failure : breaker.recentFailures(now)) {
            int colon = failure.indexOf(':');
            recent.put(new JSONObject()
                    .put("at", StatusReport.iso(Long.parseLong(failure.substring(0, colon))))
                    .put("kind", failure.substring(colon + 1)));
        }
        breakerBlock.put("failures24h", recent);
        RimeEngine running;
        synchronized (RimeAndroid.class) {
            running = engine;
        }
        String chosen = Prefs.chineseEngine(context);
        String active;
        if (!Prefs.chineseInputEnabled(context)) {
            active = "off";
        } else if (Prefs.CHINESE_ENGINE_CLASSIC.equals(chosen) || breaker.tripped()) {
            active = "classic";
        } else if (running != null && running.isReady()) {
            active = "rime";
        } else {
            active = "classic";
        }
        long lastAt = prefs.getLong(KEY_LAST_START_AT, 0);
        return new JSONObject()
                .put("engine", chosen)
                .put("active", active)
                .put("rimeState", running == null ? "not-loaded" : running.state().name().toLowerCase(java.util.Locale.ROOT))
                .put("rimeDetail", running == null ? JSONObject.NULL : running.detail())
                .put("lastStart", prefs.getString(KEY_LAST_START, "").isEmpty() ? JSONObject.NULL : prefs.getString(KEY_LAST_START, ""))
                .put("lastStartAt", lastAt > 0 ? StatusReport.iso(lastAt) : JSONObject.NULL)
                .put("hangs", running == null ? 0 : running.hangs())
                .put("breaker", breakerBlock);
    }

    /** The real phone: native library, assets, exit records, main thread. */
    private static final class Phone implements RimeEngine.Platform {
        private final Context context;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final RimeData data = new RimeData();

        Phone(Context context) {
            this.context = context;
        }

        @Override
        public boolean loadLibrary() {
            return RimeNative.load();
        }

        @Override
        public RimeEngine.Dirs installData() {
            return data.install(path -> context.getAssets().open(path), base(context));
        }

        @Override
        public String installError() {
            return data.error();
        }

        @Override
        public RimeBreaker.PreviousExit previousExit(long since) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return RimeBreaker.PreviousExit.UNKNOWN;
            }
            try {
                ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16);
                for (ApplicationExitInfo exit : exits) {
                    if (exit.getTimestamp() < since || exit.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        continue;
                    }
                    String trace = trace(exit);
                    // A tombstone that does not mention our library is another native
                    // library's crash (speech); without a tombstone, count it.
                    if (trace == null || trace.contains("libvfrime")) {
                        return RimeBreaker.PreviousExit.NATIVE_CRASH;
                    }
                }
            } catch (RuntimeException e) {
                return RimeBreaker.PreviousExit.UNKNOWN;
            }
            return RimeBreaker.PreviousExit.ORDINARY;
        }

        private static String trace(ApplicationExitInfo exit) {
            try (InputStream in = exit.getTraceInputStream()) {
                if (in == null) {
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < (4 << 20)) {
                    out.write(buffer, 0, n);
                }
                // A protobuf tombstone keeps file names as plain strings.
                return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public void postDelayed(Runnable task, long delayMs) {
            main.postDelayed(task, delayMs);
        }

        @Override
        public void recordStart(String outcome) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAST_START, outcome)
                    .putLong(KEY_LAST_START_AT, System.currentTimeMillis())
                    .apply();
        }
    }
}
