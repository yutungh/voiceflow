package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * The USB key path: bytes in, the key saved, and an answer that says only its length.
 * The adb side (content write from the shell's stdin) is not exercised here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KeyProvisionProviderTest {
    private static final String KEY = "abcdEFGH1234ijklMNOP5678qrstUVWX";
    private static final Uri URI = Uri.parse("content://" + KeyProvisionProvider.AUTHORITY + "/keys/soniox");

    private Context context;
    private KeyProvisionProvider provider;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.shared(context).edit().clear().commit();
        KeyProvisionProvider.resetForTest();
        provider = Robolectric.setupContentProvider(KeyProvisionProvider.class, KeyProvisionProvider.AUTHORITY);
    }

    private KeyProvisionProvider.Result send(String text) throws Exception {
        return provider.receive(Prefs.PROVIDER_SONIOX,
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void aPipedKeyIsTrimmedAndSaved() throws Exception {
        KeyProvisionProvider.Result result = send(KEY + "\r\n");
        assertEquals("saved", result.state);
        assertEquals(KEY.length(), result.length);
        assertEquals(KEY, Prefs.sonioxApiKey(context));
    }

    @Test
    public void theAnswerHasTheLengthNeverTheKey() throws Exception {
        provider.openFile(URI, "w").close(); // a real write: the pipe's other end saves on its own thread
        Cursor cursor = provider.query(URI, null, null, null, null);
        assertTrue(cursor.moveToFirst());
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            all.append(cursor.getColumnName(i)).append('=').append(cursor.getString(i)).append(' ');
        }
        assertEquals("soniox", cursor.getString(cursor.getColumnIndexOrThrow("provider")));
        assertTrue(all.toString(), all.toString().contains("state=refused: empty"));
        Prefs.setSonioxApiKey(context, KEY);
        cursor = provider.query(URI, null, null, null, null);
        cursor.moveToFirst();
        assertEquals(KEY.length(), cursor.getInt(cursor.getColumnIndexOrThrow("stored")));
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            assertFalse(String.valueOf(cursor.getString(i)).contains(KEY.substring(0, 8)));
        }
    }

    @Test
    public void badInputIsRefusedAndNothingIsSaved() throws Exception {
        assertEquals("refused: empty", send(" \n").state);
        assertEquals("refused: too short", send("short").state);
        assertEquals("refused: not one line of printable characters", send("abcd efgh ijkl mnop").state);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < KeyProvisionProvider.MAX_BYTES + 10; i++) {
            huge.append('a');
        }
        assertEquals("refused: too long", send(huge.toString()).state);
        assertEquals("", Prefs.sonioxApiKey(context));
    }

    @Test
    public void onlyTheSonioxKeyPathIsServed() {
        assertEquals("soniox", KeyProvisionProvider.providerOf(URI));
        assertEquals("", KeyProvisionProvider.providerOf(Uri.parse("content://x/keys/openai")));
        assertEquals("", KeyProvisionProvider.providerOf(Uri.parse("content://x/other/soniox")));
        assertNull(provider.query(Uri.parse("content://" + KeyProvisionProvider.AUTHORITY + "/keys/openai"),
                null, null, null, null));
    }

    @Test
    public void theManifestGuardsItWithDump() throws Exception {
        ProviderInfo info = context.getPackageManager().resolveContentProvider(KeyProvisionProvider.AUTHORITY,
                PackageManager.GET_META_DATA);
        assertEquals("android.permission.DUMP", info.readPermission);
        assertEquals("android.permission.DUMP", info.writePermission);
        assertTrue(info.exported);
    }
}
