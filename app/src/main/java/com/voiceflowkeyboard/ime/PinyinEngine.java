package com.voiceflowkeyboard.ime;

import android.content.res.AssetManager;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Owns the pinyin data and keeps loading it off the UI thread.
 *
 * <p>The dictionary is ~9.5 MB of text (0.8.9, Wanxiang) and the 9-key projection is a sort over
 * ~344k keys, so neither is built until Chinese is actually used, and the 9-key
 * index is built only if the keypad layout is actually chosen. Most sessions
 * never pay for either.
 *
 * <p>All fields are written on the main thread only. Background work produces an
 * immutable result which is then published back through the handler, so there is
 * no shared mutable state to synchronise.
 */
final class PinyinEngine {

    private static final String TAG = "PinyinEngine";
    private static final String ASSET = "pinyin_dict.txt";

    interface ReadyListener {
        /** Called on the main thread once a requested composer became available. */
        void onPinyinEngineReady();
    }

    private final AssetManager assets;
    private final Executor background;
    private final Handler mainHandler;
    private final ReadyListener listener;
    /** Rime (0.9.0), or null when this keyboard has none; {@link #rimeWanted} says whether to use it. */
    private final RimeEngine rime;
    private final java.util.function.BooleanSupplier rimeWanted;
    private RimeConverter rimeLetters;
    private RimeConverter rimeKeypad;

    private PinyinDictionary dictionary;
    private PinyinComposer letterComposer;
    private PinyinComposer keypadComposer;
    private boolean loadingDictionary;
    private boolean loadingKeypad;
    private boolean failed;

    PinyinEngine(AssetManager assets, Executor background, Handler mainHandler, ReadyListener listener) {
        this(assets, background, mainHandler, listener, null, () -> false);
    }

    PinyinEngine(AssetManager assets, Executor background, Handler mainHandler, ReadyListener listener,
                 RimeEngine rime, java.util.function.BooleanSupplier rimeWanted) {
        this.assets = assets;
        this.background = background;
        this.mainHandler = mainHandler;
        this.listener = listener;
        this.rime = rime;
        this.rimeWanted = rimeWanted;
    }

    /** True when the composer for {@code mode} can be used right now. */
    boolean isReady(InputMode mode) {
        return composerFor(mode) != null;
    }

    boolean hasFailed() {
        return failed;
    }

    /**
     * The engine for {@code mode}, or null while Classic is still being
     * prepared. With Rime chosen (0.9.0) it is Rime with Classic behind it:
     * Classic reads the buffer and answers whenever Rime cannot, so it must be
     * ready first.
     */
    PinyinConverter composerFor(InputMode mode) {
        if (!mode.isChinese()) {
            return null;
        }
        PinyinComposer classic = mode.isKeypad() ? keypadComposer : letterComposer;
        if (classic == null || rime == null || !rimeWanted.getAsBoolean()) {
            return classic;
        }
        if (mode.isKeypad()) {
            if (rimeKeypad == null || rimeKeypad.classic() != classic) {
                rimeKeypad = new RimeConverter(classic, rime, true, true);
            }
            return rimeKeypad;
        }
        if (rimeLetters == null || rimeLetters.classic() != classic) {
            rimeLetters = new RimeConverter(classic, rime, false, true);
        }
        return rimeLetters;
    }

    /** Rime, or null when this keyboard has none. */
    RimeEngine rime() {
        return rime;
    }

    /**
     * Kicks off whatever loading {@code mode} still needs. Safe to call on every
     * mode switch; it no-ops once the pieces exist.
     */
    void prepare(InputMode mode) {
        if (!mode.isChinese() || failed) {
            return;
        }
        if (rime != null && rimeWanted.getAsBoolean()) {
            // Off the UI thread; Classic serves until it is ready.
            rime.start();
        }
        if (dictionary == null) {
            loadDictionary(mode);
            return;
        }
        if (mode.isKeypad() && keypadComposer == null) {
            buildKeypadIndex();
        }
    }

    private void loadDictionary(InputMode requested) {
        if (loadingDictionary) {
            return;
        }
        loadingDictionary = true;
        background.execute(() -> {
            PinyinDictionary loaded = null;
            try (InputStream stream = assets.open(ASSET)) {
                loaded = PinyinDictionary.load(stream);
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "Could not load " + ASSET, error);
            }
            PinyinDictionary result = loaded;
            mainHandler.post(() -> {
                loadingDictionary = false;
                if (result == null) {
                    failed = true;
                    notifyReady();
                    return;
                }
                dictionary = result;
                letterComposer = new PinyinComposer(result);
                if (requested.isKeypad()) {
                    buildKeypadIndex();
                }
                notifyReady();
            });
        });
    }

    private void buildKeypadIndex() {
        if (loadingKeypad || dictionary == null) {
            return;
        }
        loadingKeypad = true;
        PinyinDictionary source = dictionary;
        try {
            submitKeypadIndexBuild(source);
        } catch (RejectedExecutionException shuttingDown) {
            // The keyboard was destroyed while the dictionary loaded; there is
            // no one left to build the index for.
            loadingKeypad = false;
        }
    }

    private void submitKeypadIndexBuild(PinyinDictionary source) {
        background.execute(() -> {
            PinyinKeypadLookup index = null;
            try {
                index = new PinyinKeypadLookup(PinyinDigitIndex.build(source));
            } catch (RuntimeException error) {
                Log.e(TAG, "Could not build the 9-key index", error);
            }
            PinyinKeypadLookup result = index;
            mainHandler.post(() -> {
                loadingKeypad = false;
                if (result != null) {
                    keypadComposer = new PinyinComposer(result);
                }
                notifyReady();
            });
        });
    }

    private void notifyReady() {
        if (listener != null) {
            listener.onPinyinEngineReady();
        }
    }
}
