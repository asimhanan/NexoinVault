package com.example.nexoinvaulit;

import android.content.Context;
import android.net.Uri;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted, app-private media storage. Format: 12-byte IV || AES-GCM ciphertext and tag. */
public class VaultStorageManager {
    public static final String ENCRYPTED_SUFFIX = ".nvlt";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Context context;

    public VaultStorageManager(Context context) { this.context = context.getApplicationContext(); }

    public File getVaultRoot() {
        File root = new File(context.getFilesDir(), "SecureVault");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Cannot create private vault directory");
        return root;
    }

    public File getFolder(String folderName) {
        String safeName = safeFolderName(folderName);
        File root = getVaultRoot();
        File folder = new File(root, safeName);
        if (!folder.getParentFile().equals(root)) throw new SecurityException("Invalid folder path");
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Cannot create vault folder");
        return folder;
    }

    public boolean createFolder(String folderName) {
        File folder = new File(getVaultRoot(), safeFolderName(folderName));
        return folder.mkdir();
    }

    public List<File> getFolders() {
        List<File> folders = new ArrayList<>();
        File[] files = getVaultRoot().listFiles();
        if (files != null) for (File f : files) if (f.isDirectory()) folders.add(f);
        return folders;
    }

    /** Import streams from a document-provider URI without creating a plaintext intermediate file. */
    public File importUriToFolder(Uri uri, File targetFolder, String filename) throws Exception {
        if (!isInsideVault(targetFolder) || !targetFolder.isDirectory()) throw new SecurityException("Invalid destination folder");
        String safeFilename = safeFilename(filename);
        if (!safeFilename.toLowerCase(Locale.ROOT).endsWith(ENCRYPTED_SUFFIX)) safeFilename += ENCRYPTED_SUFFIX;
        File destination = uniqueFile(targetFolder, safeFilename);
        File temp = new File(targetFolder, ".import-" + UUID.randomUUID() + ".tmp");
        byte[] key = VaultSession.requireMasterKey();
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("The selected file could not be opened");
            encrypt(raw, temp, key);
            if (!temp.renameTo(destination)) throw new IllegalStateException("Could not finalize encrypted file");
            return destination;
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            if (temp.exists()) temp.delete();
        }
    }

    /** Files from older versions were copied as plaintext. Encrypt them in place at next unlock. */
    public int migrateLegacyFiles() throws Exception {
        byte[] key = VaultSession.requireMasterKey();
        int migrated = 0;
        try {
            File[] folders = getVaultRoot().listFiles();
            if (folders == null) return 0;
            for (File folder : folders) {
                if (!folder.isDirectory()) continue;
                File[] entries = folder.listFiles();
                if (entries == null) continue;
                for (File oldFile : entries) {
                    String lower = oldFile.getName().toLowerCase(Locale.ROOT);
                    if (!oldFile.isFile() || lower.endsWith(ENCRYPTED_SUFFIX) || oldFile.getName().startsWith(".import-") || oldFile.getName().startsWith(".migrate-")) continue;
                    String encryptedName = oldFile.getName() + ENCRYPTED_SUFFIX;
                    File encryptedFile = uniqueFile(folder, encryptedName);
                    File temp = new File(folder, ".migrate-" + UUID.randomUUID() + ".tmp");
                    try (InputStream input = new BufferedInputStream(new FileInputStream(oldFile))) {
                        encrypt(input, temp, key);
                        if (!temp.renameTo(encryptedFile)) throw new IllegalStateException("Could not finalize migrated file");
                        if (!oldFile.delete()) {
                            encryptedFile.delete();
                            throw new IllegalStateException("Could not remove old plaintext file");
                        }
                        migrated++;
                    } finally {
                        if (temp.exists()) temp.delete();
                    }
                }
            }
            return migrated;
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    /** Returns a decrypted temporary cache file, or the legacy file unchanged if still plaintext. */
    public File createDecryptedCacheFile(File storedFile) throws Exception {
        if (!isInsideVault(storedFile) || !storedFile.isFile()) throw new SecurityException("Invalid vault file");
        if (!storedFile.getName().toLowerCase(Locale.ROOT).endsWith(ENCRYPTED_SUFFIX)) return storedFile;
        File previewDir = new File(context.getCacheDir(), "vault_previews");
        if (!previewDir.exists() && !previewDir.mkdirs()) throw new IllegalStateException("Cannot create preview cache");
        File cache = new File(previewDir, "vault-preview-" + UUID.randomUUID() + extensionFor(storedFile.getName()));
        byte[] key = VaultSession.requireMasterKey();
        try (InputStream input = new BufferedInputStream(new FileInputStream(storedFile));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(cache))) {
            byte[] iv = new byte[IV_BYTES];
            int ivRead = 0;
            while (ivRead < IV_BYTES) {
                int count = input.read(iv, ivRead, IV_BYTES - ivRead);
                if (count < 0) break;
                ivRead += count;
            }
            if (ivRead != IV_BYTES) throw new IllegalStateException("Encrypted file is incomplete");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            try (CipherInputStream decrypted = new CipherInputStream(input, cipher)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = decrypted.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            output.flush();
            return cache;
        } catch (Exception error) {
            cache.delete();
            throw error;
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    /** Replace a saved encrypted image with a newly transformed plaintext image. */
    public void replaceEncryptedImage(File storedFile, File plaintextImage) throws Exception {
        if (!isInsideVault(storedFile) || !storedFile.isFile()
                || !storedFile.getName().toLowerCase(Locale.ROOT).endsWith(ENCRYPTED_SUFFIX)) {
            throw new SecurityException("Invalid encrypted image");
        }
        if (plaintextImage == null || !plaintextImage.isFile()) throw new IllegalArgumentException("Missing transformed image");
        File temp = new File(storedFile.getParentFile(), ".rotate-" + UUID.randomUUID() + ".tmp");
        byte[] key = VaultSession.requireMasterKey();
        try (InputStream input = new BufferedInputStream(new FileInputStream(plaintextImage))) {
            encrypt(input, temp, key);
            // Both files are in the same vault directory, so rename atomically replaces the old ciphertext.
            Os.rename(temp.getAbsolutePath(), storedFile.getAbsolutePath());
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            if (temp.exists()) temp.delete();
        }
    }

    /** Delete only a regular file whose canonical path is inside the private vault. */
    public boolean deleteVaultFile(File storedFile) {
        if (!isInsideVault(storedFile) || !storedFile.isFile()) throw new SecurityException("Invalid vault file");
        return storedFile.delete();
    }

    /** Create a plaintext cache copy for Android sharing; stale copies are removed after 24 hours. */
    public File createShareCopy(File storedFile) throws Exception {
        File decrypted = createDecryptedCacheFile(storedFile);
        File shareDir = new File(context.getCacheDir(), "vault_shares");
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            if (!decrypted.equals(storedFile)) decrypted.delete();
            throw new IllegalStateException("Cannot create share cache");
        }
        clearStaleShareFiles(shareDir);
        File shareFile = new File(shareDir, "shared-" + UUID.randomUUID() + extensionFor(storedFile.getName()));
        try (InputStream input = new BufferedInputStream(new FileInputStream(decrypted));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(shareFile))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            return shareFile;
        } catch (Exception error) {
            shareFile.delete();
            throw error;
        } finally {
            if (!decrypted.equals(storedFile)) decrypted.delete();
        }
    }

    private static void clearStaleShareFiles(File shareDir) {
        File[] files = shareDir.listFiles((dir, name) -> name.startsWith("shared-"));
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        if (files != null) for (File file : files) if (file.lastModified() < cutoff) file.delete();
    }

    public void clearStalePreviewFiles() {
        File previewDir = new File(context.getCacheDir(), "vault_previews");
        File[] files = previewDir.listFiles((dir, name) -> name.startsWith("vault-preview-"));
        if (files != null) for (File file : files) file.delete();
    }

    private static void encrypt(InputStream input, File outputFile, byte[] key) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            output.write(iv);
            try (CipherOutputStream encrypted = new CipherOutputStream(output, cipher)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) encrypted.write(buffer, 0, count);
            }
        }
    }

    private boolean isInsideVault(File file) {
        try {
            String root = getVaultRoot().getCanonicalPath() + File.separator;
            return file.getCanonicalPath().startsWith(root);
        } catch (Exception e) { return false; }
    }

    private static String safeFolderName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9 _-]{1,64}")) throw new IllegalArgumentException("Invalid folder name");
        return name;
    }

    private static String safeFilename(String name) {
        String safe = name == null ? "" : new File(name).getName().replaceAll("[^A-Za-z0-9._() -]", "_");
        safe = safe.trim();
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "file-" + System.currentTimeMillis();
        if (safe.length() > 180) safe = safe.substring(0, 180);
        return safe;
    }

    private static File uniqueFile(File folder, String filename) {
        File candidate = new File(folder, filename);
        if (!candidate.exists()) return candidate;
        int suffix = 1;
        do { candidate = new File(folder, suffix++ + "_" + filename); } while (candidate.exists());
        return candidate;
    }

    private static String extensionFor(String encryptedFilename) {
        String original = encryptedFilename.toLowerCase(Locale.ROOT).endsWith(ENCRYPTED_SUFFIX)
                ? encryptedFilename.substring(0, encryptedFilename.length() - ENCRYPTED_SUFFIX.length())
                : encryptedFilename;
        int dot = original.lastIndexOf('.');
        return dot >= 0 && dot < original.length() - 1 ? original.substring(dot) : ".bin";
    }
}
