package com.assessment;

import com.assessment.util.Crypto;
import com.assessment.util.Hashing;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class UtilTest {

    @Test
    void sha256_knownVector() {
        // SHA-256 of empty string
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Hashing.sha256Hex(new byte[0]));
    }

    @Test
    void md5_knownVector() {
        // MD5 of empty string
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Hashing.md5Hex(new byte[0]));
    }

    @Test
    void aesGcmRoundTrip() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256);
        SecretKey key = kg.generateKey();

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        byte[] plain = "the hidden answer".getBytes(StandardCharsets.UTF_8);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);

        // Prepend nonce as our helper expects.
        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);

        byte[] out = Crypto.aesGcmPrefixedNonce(key.getEncoded(), blob);
        assertEquals("the hidden answer", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void decodeKey_handlesBase64() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String b64 = Base64.getEncoder().encodeToString(raw);
        assertArrayEquals(raw, Crypto.decodeKey(b64));
    }
}
