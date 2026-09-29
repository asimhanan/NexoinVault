package com.example.nexoinvaulit;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password and wrapped vault-key persistence. The unwrapped master key is never persisted. */
public final class PasswordUtils {
    private static final String PREFS = "security";
    private static final int ITERATIONS = 120_000; // Matches the supplied encryption design.
    private static final int SALT_BYTES = 16;
    private static final int KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String AAD = "NexoinVault:wrapped-master-key:v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSet(Context context) {
        return prefs(context).contains("hash");
    }

    /** Creates credentials and a random AES-256 master key on first-run setup. */
    public static void setPassword(Context context, String password) throws Exception {
        if (isSet(context)) throw new IllegalStateException("A password is already configured");
        byte[] masterKey = new byte[KEY_BYTES];
        RANDOM.nextBytes(masterKey);
        try {
            saveCredentials(context, password, masterKey);
            VaultSession.unlock(masterKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
        }
    }

    /** Checks password without loading the vault key. Supports the original app's verifier. */
    public static boolean verify(Context context, String password) throws Exception {
        SharedPreferences p = prefs(context);
        if (!p.contains("hash")) return false;
        String saltName = p.contains("verifierSalt") ? "verifierSalt" : "salt";
        String encodedSalt = p.getString(saltName, null);
        String expected = p.getString("hash", null);
        if (encodedSalt == null || expected == null) return false;
        byte[] actual = derive(password, Base64.decode(encodedSalt, Base64.NO_WRAP));
        byte[] expectedBytes;
        try {
            expectedBytes = Base64.decode(expected, Base64.NO_WRAP);
        } catch (IllegalArgumentException badValue) {
            Arrays.fill(actual, (byte) 0);
            return false;
        }
        boolean matches = MessageDigest.isEqual(actual, expectedBytes);
        Arrays.fill(actual, (byte) 0);
        Arrays.fill(expectedBytes, (byte) 0);
        return matches;
    }

    /** Verify, unwrap, and retain the master key in memory. Migrates old verifier-only installs. */
    public static boolean unlock(Context context, String password) throws Exception {
        if (!verify(context, password)) return false;
        SharedPreferences p = prefs(context);
        if (!p.contains("wrappedKey") || !p.contains("kekSalt")) {
            // Older versions had only a PBKDF2 verifier and stored media as plaintext.
            byte[] newMasterKey = new byte[KEY_BYTES];
            RANDOM.nextBytes(newMasterKey);
            try {
                saveCredentials(context, password, newMasterKey);
                VaultSession.unlock(newMasterKey);
            } finally {
                Arrays.fill(newMasterKey, (byte) 0);
            }
            return true;
        }
        byte[] kekSalt = Base64.decode(p.getString("kekSalt", ""), Base64.NO_WRAP);
        byte[] kek = derive(password, kekSalt);
        byte[] wrapped = Base64.decode(p.getString("wrappedKey", ""), Base64.NO_WRAP);
        byte[] key = null;
        try {
            key = unwrapKey(kek, wrapped);
            if (key.length != KEY_BYTES) throw new IllegalStateException("Invalid vault key size");
            VaultSession.unlock(key);
            return true;
        } finally {
            Arrays.fill(kek, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    /** Re-wraps the existing master key under a fresh KEK; vault files are not rewritten. */
    public static void changePassword(Context context, String oldPassword, String newPassword) throws Exception {
        if (!verify(context, oldPassword)) throw new SecurityException("Current password is incorrect");
        SharedPreferences p = prefs(context);
        byte[] masterKey;
        if (p.contains("wrappedKey") && p.contains("kekSalt")) {
            byte[] oldKek = derive(oldPassword, Base64.decode(p.getString("kekSalt", ""), Base64.NO_WRAP));
            try {
                masterKey = unwrapKey(oldKek, Base64.decode(p.getString("wrappedKey", ""), Base64.NO_WRAP));
            } finally {
                Arrays.fill(oldKek, (byte) 0);
            }
        } else {
            // Upgrade credentials from versions that stored only a verifier.
            masterKey = new byte[KEY_BYTES];
            RANDOM.nextBytes(masterKey);
        }
        try {
            saveCredentials(context, newPassword, masterKey);
            VaultSession.unlock(masterKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
        }
    }

    private static void saveCredentials(Context context, String password, byte[] masterKey) throws Exception {
        byte[] verifierSalt = randomBytes(SALT_BYTES);
        byte[] kekSalt = randomBytes(SALT_BYTES); // Separate salt: verifier must not reveal the KEK.
        byte[] verifier = derive(password, verifierSalt);
        byte[] kek = derive(password, kekSalt);
        byte[] wrapped = wrapKey(kek, masterKey);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString("verifierSalt", b64(verifierSalt))
                .putString("hash", b64(verifier))
                .putString("kekSalt", b64(kekSalt))
                .putString("wrappedKey", b64(wrapped))
                .remove("salt"); // Do not retain a legacy salt with old semantics.
        try {
            if (!editor.commit()) throw new IllegalStateException("Could not persist vault credentials");
        } finally {
            Arrays.fill(verifier, (byte) 0);
            Arrays.fill(kek, (byte) 0);
            Arrays.fill(wrapped, (byte) 0);
        }
    }

    private static byte[] wrapKey(byte[] kek, byte[] masterKey) throws Exception {
        byte[] iv = randomBytes(GCM_IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(AAD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(masterKey);
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        Arrays.fill(encrypted, (byte) 0);
        return result;
    }

    private static byte[] unwrapKey(byte[] kek, byte[] wrapped) throws Exception {
        if (wrapped.length < GCM_IV_BYTES + 16) throw new SecurityException("Invalid wrapped vault key");
        byte[] iv = Arrays.copyOfRange(wrapped, 0, GCM_IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(wrapped, GCM_IV_BYTES, wrapped.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(AAD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            return cipher.doFinal(encrypted);
        } finally {
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static byte[] derive(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String b64(byte[] bytes) { return Base64.encodeToString(bytes, Base64.NO_WRAP); }
}
