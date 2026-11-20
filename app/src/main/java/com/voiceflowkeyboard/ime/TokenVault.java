package com.voiceflowkeyboard.ime;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the gateway token with AES-GCM under a key that lives in the Android
 * Keystore and never leaves it. What is stored is "v1:" + base64(iv) + ":" +
 * base64(ciphertext). The plain token exists only in memory, for one request.
 */
final class TokenVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "voiceflow_sync_token_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    /** Supplies the key; the Android Keystore on a phone, a plain key in unit tests. */
    interface KeySource {
        SecretKey key() throws Exception;
    }

    static final KeySource ANDROID_KEYSTORE = () -> {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    };

    private TokenVault() {
    }

    static String seal(KeySource keys, String token) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keys.key());
        byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        return "v1:" + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP);
    }

    static String open(KeySource keys, String stored) throws Exception {
        if (stored == null || !stored.startsWith("v1:")) {
            throw new IllegalStateException("no token stored");
        }
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalStateException("damaged token record");
        }
        byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
        byte[] sealed = Base64.decode(parts[2], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    }
}
