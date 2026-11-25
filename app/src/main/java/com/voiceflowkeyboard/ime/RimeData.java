package com.voiceflowkeyboard.ime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Puts Rime's compiled data where librime can read it (0.9.0).
 *
 * <p>The APK carries the data under {@code assets/rime/} (54 MB, compressed in
 * the APK) with a manifest: a data version, then one line per file with its
 * size and SHA-256 (written by scripts/rime-android/build.sh). On first use,
 * and after an update that changes the data, it is copied into app storage
 * ({@code <base>/data-<version>/}), every file checked against the manifest,
 * and the folder renamed into place only when complete, so a copy cut short
 * (the keyboard killed half way) is never used. Later starts reuse it. Other
 * versions' folders are removed once the new one is in place.
 *
 * <p>What Rime learns lives apart, in {@code <base>/user/}, and survives data
 * updates.
 *
 * <p>Pure Java over an {@link Assets} source, so it is unit tested on the JVM.
 */
final class RimeData {

    interface Assets {
        InputStream open(String path) throws IOException;
    }

    static final String ROOT = "rime";
    static final String MANIFEST = ROOT + "/manifest.txt";
    private static final String COMPLETE = ".complete";
    /** Room kept free beyond the data itself. */
    static final long SPARE_BYTES = 20L * 1024 * 1024;

    static final class Manifest {
        final String version;
        final List<String> paths = new ArrayList<>();
        final List<Long> sizes = new ArrayList<>();
        final List<String> hashes = new ArrayList<>();

        Manifest(String version) {
            this.version = version;
        }

        long totalBytes() {
            long total = 0;
            for (long size : sizes) {
                total += size;
            }
            return total;
        }
    }

    private String error = "";

    String error() {
        return error;
    }

    static Manifest readManifest(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Manifest manifest = null;
        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = line.trim().split(" ");
            if (f.length == 2 && f[0].equals("version")) {
                manifest = new Manifest(f[1]);
            } else if (f.length == 4 && f[0].equals("file") && manifest != null) {
                String path = f[3];
                if (!path.matches("(shared|build)/[A-Za-z0-9_.-]+")) {
                    throw new IOException("bad path in manifest: " + path);
                }
                manifest.paths.add(path);
                manifest.sizes.add(Long.parseLong(f[1]));
                manifest.hashes.add(f[2].toLowerCase(Locale.ROOT));
            }
        }
        if (manifest == null || !manifest.version.matches("[0-9a-f]{8,64}") || manifest.paths.isEmpty()) {
            throw new IOException("no usable manifest");
        }
        return manifest;
    }

    /**
     * Installs the data under {@code base} if needed and returns the folders,
     * or null with {@link #error} set ("no-manifest", "no-space", "copy", "hash").
     */
    RimeEngine.Dirs install(Assets assets, File base) {
        Manifest manifest;
        try (InputStream in = assets.open(MANIFEST)) {
            manifest = readManifest(in);
        } catch (IOException | RuntimeException e) {
            error = "no-manifest";
            return null;
        }
        File target = new File(base, "data-" + manifest.version);
        File user = new File(base, "user");
        if (!new File(target, COMPLETE).isFile()) {
            base.mkdirs();
            if (base.getUsableSpace() < manifest.totalBytes() + SPARE_BYTES) {
                error = "no-space";
                return null;
            }
            File staging = new File(base, "staging-" + manifest.version + "-" + System.nanoTime());
            if (!copyAll(assets, manifest, staging)) {
                RimeEngine.clearUserData(staging);
                staging.delete();
                return null;
            }
            try {
                touch(new File(staging, COMPLETE));
            } catch (IOException e) {
                error = "copy";
                return null;
            }
            if (target.exists()) {
                // An incomplete folder from an older attempt.
                RimeEngine.clearUserData(target);
                target.delete();
            }
            if (!staging.renameTo(target)) {
                error = "copy";
                return null;
            }
        }
        removeOtherVersions(base, target);
        user.mkdirs();
        return new RimeEngine.Dirs(new File(target, "shared"), new File(target, "build"), user, manifest.version);
    }

    private boolean copyAll(Assets assets, Manifest manifest, File staging) {
        byte[] buffer = new byte[1 << 16];
        for (int i = 0; i < manifest.paths.size(); i++) {
            String path = manifest.paths.get(i);
            File out = new File(staging, path);
            out.getParentFile().mkdirs();
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                error = "hash";
                return false;
            }
            long written = 0;
            try (InputStream in = assets.open(ROOT + "/" + path); OutputStream o = new FileOutputStream(out)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    o.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    written += n;
                }
            } catch (IOException e) {
                error = "copy";
                return false;
            }
            if (written != manifest.sizes.get(i) || !hex(digest.digest()).equals(manifest.hashes.get(i))) {
                error = "hash";
                return false;
            }
        }
        return true;
    }

    private static void removeOtherVersions(File base, File keep) {
        File[] children = base.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (child.isDirectory() && !child.equals(keep) && (name.startsWith("data-") || name.startsWith("staging-"))) {
                RimeEngine.clearUserData(child);
                child.delete();
            }
        }
    }

    private static void touch(File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write('1');
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }
}
