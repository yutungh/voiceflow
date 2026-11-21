package com.voiceflowkeyboard.ime;

/**
 * The native half of the Rime engine (0.9.0): libvfrime.so, built by
 * scripts/rime-android/build.sh from librime (BSD-3) and VoiceFlow's own JNI
 * bridge (app/src/main/cpp, MIT). The natives are registered in JNI_OnLoad,
 * the library's only exported symbol.
 *
 * <p>Every call is made from {@link RimeEngine}'s single worker thread, never
 * the UI thread.
 */
final class RimeNative implements RimeEngine.Backend {

    private static final String LIBRARY = "vfrime";

    private static boolean loaded;
    private static String loadError = "";

    /** Loads the library once; false (with {@link #loadError}) when it is missing or will not load. */
    static synchronized boolean load() {
        if (loaded) {
            return true;
        }
        try {
            System.loadLibrary(LIBRARY);
            loaded = true;
            loadError = "";
        } catch (UnsatisfiedLinkError | SecurityException error) {
            loadError = error.getClass().getSimpleName();
        }
        return loaded;
    }

    static synchronized String loadError() {
        return loadError;
    }

    @Override
    public String start(String shared, String prebuilt, String user) {
        return nativeStart(shared, prebuilt, user);
    }

    @Override
    public void stop() {
        nativeStop();
    }

    @Override
    public String[] query(int mode, String input, int limit) {
        return nativeQuery(mode, input, limit);
    }

    @Override
    public boolean learn(int mode, String input, String text) {
        return nativeLearn(mode, input, text);
    }

    @Override
    public String version() {
        return nativeVersion();
    }

    private static native String nativeStart(String shared, String prebuilt, String user);

    private static native void nativeStop();

    private static native String[] nativeQuery(int mode, String input, int limit);

    private static native boolean nativeLearn(int mode, String input, String text);

    private static native String nativeVersion();
}
