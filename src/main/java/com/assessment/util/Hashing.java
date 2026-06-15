package com.assessment.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Byte-level integrity helpers for Layer 1. */
public final class Hashing {
    private Hashing() {}

    public static String sha256Hex(byte[] data) {
        return digestHex("SHA-256", data);
    }

    public static String md5Hex(byte[] data) {
        return digestHex("MD5", data);
    }

    private static String digestHex(String algo, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
