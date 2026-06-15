package com.assessment.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Decryption helpers.
 *
 * Main puzzle uses RSA-2048 PKCS#1v1.5 (see rsaPkcs1v15Decrypt).
 * AES helpers remain for the optional design challenge (AES-GCM/CBC).
 */
public final class Crypto {
    private Crypto() {}

    // ---- RSA ----------------------------------------------------------------

    /**
     * Load an RSA private key from a PKCS#8 PEM string
     * (-----BEGIN PRIVATE KEY----- / -----END PRIVATE KEY-----).
     */
    public static PrivateKey loadRsaPrivateKey(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(stripped);
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Decrypt one RSA-2048 PKCS#1v1.5 ciphertext block. */
    public static byte[] rsaPkcs1v15Decrypt(PrivateKey key, byte[] ciphertext) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.DECRYPT_MODE, key);
        return c.doFinal(ciphertext);
    }

    // ---- AES (optional design challenge) ------------------------------------

    public static byte[] decodeKey(String key) {
        try { return Base64.getDecoder().decode(key.trim()); }
        catch (IllegalArgumentException e) { return hex(key.trim()); }
    }

    /** AES-256-GCM with a 12-byte nonce prepended to the ciphertext. */
    public static byte[] aesGcmPrefixedNonce(byte[] key, byte[] blob) throws Exception {
        byte[] iv = Arrays.copyOfRange(blob, 0, 12);
        byte[] ct = Arrays.copyOfRange(blob, 12, blob.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        return c.doFinal(ct);
    }

    /** AES-256-CBC with a 16-byte IV prepended to the ciphertext. */
    public static byte[] aesCbcPrefixedIv(byte[] key, byte[] blob) throws Exception {
        byte[] iv = Arrays.copyOfRange(blob, 0, 16);
        byte[] ct = Arrays.copyOfRange(blob, 16, blob.length);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return c.doFinal(ct);
    }

    private static byte[] hex(String s) {
        int n = s.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return out;
    }
}
