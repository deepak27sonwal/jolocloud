package com.cloudbackup.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Salted-hash storage for the app's PIN lock. The PIN never leaves the device. */
final class PinUtil {
    private PinUtil() { }

    static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    static String hash(String pin, String saltHex) {
        try {
            byte[] salt = unhex(saltHex);
            byte[] key = MessageDigest.getInstance("SHA-256").digest(concat(salt, pin.getBytes("UTF-8")));
            // a few rounds of HMAC stretching so a stolen hash is not trivially brute-forced
            Mac m = Mac.getInstance("HmacSHA256");
            SecretKey k = new SecretKeySpec(key, "HmacSHA256");
            byte[] out = key;
            for (int i = 0; i < 20000; i++) { m.init(k); out = m.doFinal(out); }
            return hex(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean matches(String pin, String saltHex, String expectedHash) {
        if (saltHex.isEmpty() || expectedHash.isEmpty()) return false;
        return constantTimeEquals(hash(pin, saltHex), expectedHash);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
