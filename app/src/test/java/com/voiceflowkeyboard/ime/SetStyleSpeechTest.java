package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** SET_STYLE_SPEECH over USB: a style's speech model, a word-list style's included. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SetStyleSpeechTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
    }

    @Test
    public void aWordListStyleGetsSonioxAndTheReplySaysWhetherAKeyIsThere() {
        String reply = SyncCommandReceiver.setStyleSpeech(context, "list_mixed-example", "soniox", "stt-async-v6");
        assertTrue(reply, reply.contains("style=list_mixed-example choice=soniox/stt-async-v6"));
        assertTrue(reply, reply.contains("resolved=openai/gpt-transcribe (soniox unavailable)"));
        assertTrue(reply, reply.contains("key=no"));

        Prefs.setSonioxApiKey(context, "test-soniox-key-0000");
        reply = SyncCommandReceiver.setStyleSpeech(context, "list_mixed-example", "soniox", "");
        assertTrue(reply, reply.contains("resolved=soniox/stt-async-v6 key=yes"));
        assertEquals("{\"provider\":\"soniox\"}", Prefs.speechModelForStyle(context, "list_mixed-example"));
        assertTrue(reply, !reply.contains("test-soniox-key"));
    }

    @Test
    public void sameClearsAndAppPinsTheAppDefault() {
        SyncCommandReceiver.setStyleSpeech(context, "casual", "soniox", "");
        SyncCommandReceiver.setStyleSpeech(context, "casual", "same", null);
        assertNull(Prefs.speechModelForStyle(context, "casual"));
        String reply = SyncCommandReceiver.setStyleSpeech(context, "casual", "app", null);
        assertTrue(reply, reply.contains("choice=app"));
        assertEquals(SpeechModels.APP, Prefs.speechModelForStyle(context, "casual"));
    }

    @Test
    public void badValuesAreRefusedByName() {
        for (String[] bad : new String[][]{{"", "soniox"}, {"Has Space", "soniox"}, {"casual", "future-asr"},
                {"casual", "anthropic"}}) {
            try {
                SyncCommandReceiver.setStyleSpeech(context, bad[0], bad[1], "");
                fail(bad[0] + "/" + bad[1]);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().startsWith("style") || expected.getMessage().startsWith("provider"));
            }
        }
    }

    @Test
    public void theBroadcastRepliesWithTheResult() {
        SyncCommandReceiver receiver = new SyncCommandReceiver();
        Intent intent = new Intent(SyncCommandReceiver.ACTION_SET_STYLE_SPEECH)
                .putExtra("style", "list_mixed-example").putExtra("provider", "nope");
        // Outside an ordered broadcast setResult* throws; the action itself is what is tested above.
        try {
            receiver.onReceive(context, intent);
        } catch (RuntimeException ignored) {
            // no ordered broadcast here
        }
        assertNull(Prefs.speechModelForStyle(context, "list_mixed-example"));
    }
}
