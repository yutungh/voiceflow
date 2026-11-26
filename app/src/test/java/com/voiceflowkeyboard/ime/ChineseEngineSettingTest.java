package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Settings > Keyboard > Chinese engine (0.9.0): Rime by default, Classic on
 * request, and the keyboard's engine follows the choice, with Classic behind
 * Rime either way. Also the status file's {@code pinyin} block.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChineseEngineSettingTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Context context;
    private ExecutorService background;
    private ExecutorService worker;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        background = Executors.newSingleThreadExecutor();
        worker = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() {
        background.shutdownNow();
        worker.shutdownNow();
    }

    @Test
    public void rimeIsTheDefaultAndClassicCanBeChosen() {
        assertEquals(Prefs.CHINESE_ENGINE_RIME, Prefs.chineseEngine(context));
        Prefs.setChineseEngine(context, Prefs.CHINESE_ENGINE_CLASSIC);
        assertEquals(Prefs.CHINESE_ENGINE_CLASSIC, Prefs.chineseEngine(context));
        Prefs.setChineseEngine(context, "something else");
        assertEquals(Prefs.CHINESE_ENGINE_RIME, Prefs.chineseEngine(context));
        assertEquals("Classic", Prefs.chineseEngineLabel(Prefs.CHINESE_ENGINE_CLASSIC));
        assertEquals("Rime", Prefs.chineseEngineLabel(Prefs.CHINESE_ENGINE_RIME));
    }

    @Test
    public void theKeyboardsEngineFollowsTheChoice() throws Exception {
        RimeEngineTest.FakePlatform platform = new RimeEngineTest.FakePlatform();
        platform.base = folder.newFolder("rime");
        RimeEngine rime = new RimeEngine(new RimeEngineTest.FakeBackend(), platform,
                new RimeBreaker(new RimeBreakerTest.MemoryStore()), worker);
        AtomicBoolean wanted = new AtomicBoolean(true);
        PinyinEngine engine = new PinyinEngine(context.getAssets(), background,
                new Handler(Looper.getMainLooper()), () -> { }, rime, wanted::get);
        engine.prepare(InputMode.CHINESE_QWERTY);
        long until = System.currentTimeMillis() + 60_000;
        while (engine.composerFor(InputMode.CHINESE_QWERTY) == null && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        PinyinConverter chosen = engine.composerFor(InputMode.CHINESE_QWERTY);
        assertNotNull("Classic never loaded", chosen);
        assertTrue(chosen instanceof RimeConverter);
        // The same object while nothing changes, so the session keeps its engine.
        assertTrue(chosen == engine.composerFor(InputMode.CHINESE_QWERTY));
        wanted.set(false);
        assertTrue(engine.composerFor(InputMode.CHINESE_QWERTY) instanceof PinyinComposer);
        // Rime was started off the UI thread when Chinese was prepared.
        until = System.currentTimeMillis() + 5000;
        while (!rime.isReady() && System.currentTimeMillis() < until) {
            Thread.sleep(5);
        }
        assertTrue(rime.isReady());
    }

    @Test
    public void theStatusBlockSaysWhichEngineAndWhy() throws Exception {
        Prefs.setChineseInputEnabled(context, true);
        org.json.JSONObject block = RimeAndroid.statusBlock(context);
        assertEquals("rime", block.getString("engine"));
        assertEquals("classic", block.getString("active"));
        assertTrue(!block.getJSONObject("breaker").getBoolean("tripped"));
        RimeBreaker breaker = RimeAndroid.breaker(context);
        long now = System.currentTimeMillis();
        for (int i = 0; i < RimeBreaker.LIMIT; i++) {
            breaker.recordFailure(RimeBreaker.KIND_CRASH, now - RimeBreaker.LIMIT + i);
        }
        block = RimeAndroid.statusBlock(context);
        assertTrue(block.getJSONObject("breaker").getBoolean("tripped"));
        assertEquals("crash", block.getJSONObject("breaker").getString("reason"));
        assertEquals(RimeBreaker.LIMIT, block.getJSONObject("breaker").getJSONArray("failures24h").length());
        assertEquals("classic", block.getString("active"));
        // Codes and counts only.
        String text = block.toString();
        assertTrue(text, !text.contains("/data/") && !text.contains("files"));
    }
}
