package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Device id, enrolment and the sealed token. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EnrolmentTest {
    private static final String TOKEN = "vfs1.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private Context context;
    private TokenVault.KeySource savedKeys;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        SyncStore.prefs(context).edit().clear().commit();
        savedKeys = SyncStore.keys;
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        SyncStore.keys = () -> key;
    }

    @After
    public void tearDown() {
        SyncStore.keys = savedKeys;
    }

    @Test
    public void theDeviceIdIsMadeOnceAndKept() {
        String id = SyncStore.deviceId(context);
        assertTrue(id, Enrolment.validDevice(id));
        assertEquals(id, SyncStore.deviceId(context));
        assertTrue(Enrolment.newDeviceId().matches("ph[0-9a-f]{8}"));
    }

    @Test
    public void gatewayMustBeABareHttpsOrigin() {
        assertEquals("https://sync.pinewood.example", Enrolment.normalizeGateway("https://Sync.Pinewood.example/"));
        assertEquals("https://sync.pinewood.example:8443", Enrolment.normalizeGateway("https://sync.pinewood.example:8443"));
        assertEquals("https://sync.pinewood.example", Enrolment.normalizeGateway("https://sync.pinewood.example:443"));
        assertNull(Enrolment.normalizeGateway("http://sync.pinewood.example"));
        assertNull(Enrolment.normalizeGateway("https://user:pw@sync.pinewood.example"));
        assertNull(Enrolment.normalizeGateway("https://sync.pinewood.example/v1"));
        assertNull(Enrolment.normalizeGateway("https://sync.pinewood.example?x=1"));
        assertNull(Enrolment.normalizeGateway("https://sync.pinewood.example#x"));
        assertNull(Enrolment.normalizeGateway("sync.pinewood.example"));
        assertNull(Enrolment.normalizeGateway(null));
    }

    @Test
    public void enrolStoresTheTokenSealedNotPlain() throws Exception {
        Enrolment enrolment = SyncStore.enrol(context, "milo", "https://sync.pinewood.example", TOKEN);
        assertEquals("milo", enrolment.user);
        assertEquals(SyncStore.deviceId(context), enrolment.device);
        assertEquals(TOKEN, SyncStore.token(context));
        for (Object value : SyncStore.prefs(context).getAll().values()) {
            assertFalse(String.valueOf(value).contains(TOKEN));
            assertFalse(String.valueOf(value).contains("vfs1."));
        }
        assertEquals("milo", SyncStore.enrolment(context).user);
    }

    @Test
    public void badValuesLeaveThePhoneAsItWas() throws Exception {
        String[][] bad = {
                {"Milo", "https://sync.pinewood.example", TOKEN},
                {"milo", "http://sync.pinewood.example", TOKEN},
                {"milo", "https://sync.pinewood.example", "not-a-token"},
                {"milo", "https://sync.pinewood.example", TOKEN + "x"},
        };
        for (String[] attempt : bad) {
            try {
                SyncStore.enrol(context, attempt[0], attempt[1], attempt[2]);
                fail("accepted " + attempt[0] + " " + attempt[1]);
            } catch (IllegalArgumentException expected) {
                assertFalse("the message must not echo the token", expected.getMessage().contains("vfs1."));
            }
        }
        assertNull(SyncStore.enrolment(context));
    }

    @Test
    public void theKeyboardOpenTriggerRunsAtMostEveryFifteenMinutes() {
        long t = 1_800_000_000_000L;
        long interval = SyncScheduler.KEYBOARD_KICK_INTERVAL_MS;
        assertTrue(SyncStore.claimKick(context, t, interval));
        assertFalse(SyncStore.claimKick(context, t + interval - 1, interval));
        assertTrue(SyncStore.claimKick(context, t + interval, interval));
    }
}
