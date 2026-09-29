package com.example.nexoinvaulit;

import java.util.Arrays;

/** Holds the unwrapped 256-bit key only in process memory. */
public final class VaultSession {
    private static byte[] masterKey;
    private VaultSession() { }

    public static synchronized void unlock(byte[] key) {
        lock();
        masterKey = key.clone();
    }

    public static synchronized boolean isUnlocked() { return masterKey != null; }

    public static synchronized byte[] requireMasterKey() {
        if (masterKey == null) throw new IllegalStateException("Vault is locked; sign in again");
        return masterKey.clone();
    }

    public static synchronized void lock() {
        if (masterKey != null) Arrays.fill(masterKey, (byte) 0);
        masterKey = null;
    }
}
