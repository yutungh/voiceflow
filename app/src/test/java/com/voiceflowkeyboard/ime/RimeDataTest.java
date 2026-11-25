package com.voiceflowkeyboard.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Copying Rime's compiled data from the APK into app storage (0.9.0). */
public class RimeDataTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final Map<String, byte[]> assets = new HashMap<>();
    private int opens;

    private RimeData.Assets source() {
        return path -> {
            byte[] bytes = assets.get(path);
            if (bytes == null) {
                throw new FileNotFoundException(path);
            }
            opens++;
            return new ByteArrayInputStream(bytes);
        };
    }

    private static String sha(byte[] bytes) throws Exception {
        return RimeData.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void data(String version, String... pathsAndContents) throws Exception {
        StringBuilder manifest = new StringBuilder("version " + version + "\n");
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            byte[] bytes = pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8);
            assets.put("rime/" + pathsAndContents[i], bytes);
            manifest.append("file ").append(bytes.length).append(' ').append(sha(bytes)).append(' ')
                    .append(pathsAndContents[i]).append('\n');
        }
        assets.put(RimeData.MANIFEST, manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void copiesOnceAndReusesTheCopy() throws Exception {
        data("0123456789abcdef", "shared/default.yaml", "schema_list: []", "build/vf_t9.prism.bin", "PRISM");
        File base = folder.newFolder("rime");
        RimeData data = new RimeData();
        RimeEngine.Dirs dirs = data.install(source(), base);
        assertNotNull(data.error(), dirs);
        assertEquals("0123456789abcdef", dirs.dataVersion);
        assertEquals("PRISM", new String(Files.readAllBytes(new File(dirs.prebuilt, "vf_t9.prism.bin").toPath()),
                StandardCharsets.UTF_8));
        assertTrue(new File(dirs.shared, "default.yaml").isFile());
        assertTrue(dirs.user.isDirectory());
        int before = opens;
        assertNotNull(new RimeData().install(source(), base));
        assertEquals("only the manifest is read again", before + 1, opens);
    }

    @Test
    public void aCorruptCopyIsNeverUsed() throws Exception {
        data("0123456789abcdef", "build/vf_t9.prism.bin", "PRISM");
        assets.put("rime/build/vf_t9.prism.bin", "PRISN".getBytes(StandardCharsets.UTF_8));
        File base = folder.newFolder("rime");
        RimeData data = new RimeData();
        assertNull(data.install(source(), base));
        assertEquals("hash", data.error());
        assertFalse(new File(base, "data-0123456789abcdef").exists());
        File[] left = base.listFiles();
        assertEquals("the staging copy is cleaned up", 0, left == null ? 0 : left.length);
    }

    @Test
    public void aNewDataVersionReplacesTheOldButKeepsWhatWasLearned() throws Exception {
        File base = folder.newFolder("rime");
        data("1111111111111111", "build/a.bin", "one");
        RimeEngine.Dirs first = new RimeData().install(source(), base);
        assertNotNull(first);
        Files.write(new File(first.user, "vf_pinyin.userdb.txt").toPath(), "learned".getBytes(StandardCharsets.UTF_8));
        data("2222222222222222", "build/a.bin", "two");
        RimeEngine.Dirs second = new RimeData().install(source(), base);
        assertNotNull(second);
        assertFalse(new File(base, "data-1111111111111111").exists());
        assertTrue(new File(second.user, "vf_pinyin.userdb.txt").isFile());
    }

    @Test
    public void aManifestCannotPointOutsideTheDataFolder() throws Exception {
        assets.put(RimeData.MANIFEST, ("version 0123456789abcdef\nfile 1 "
                + sha("x".getBytes(StandardCharsets.UTF_8)) + " ../../escape\n").getBytes(StandardCharsets.UTF_8));
        RimeData data = new RimeData();
        assertNull(data.install(source(), folder.newFolder("rime")));
        assertEquals("no-manifest", data.error());
    }

    @Test
    public void noManifestMeansNoRime() throws Exception {
        RimeData data = new RimeData();
        assertNull(data.install(source(), folder.newFolder("rime")));
        assertEquals("no-manifest", data.error());
    }
}
