package com.example.nexoinvaulit;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends AppCompatActivity {
    private static final int MAX_IMAGE_EDGE = 2048;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private VaultStorageManager storage;
    private File storedFile;
    private File previewFile;
    private Bitmap imageBitmap;
    private boolean videoMode;
    private Button rotateButton;
    private Button deleteButton;
    private Button shareButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_viewer);
        String path = getIntent().getStringExtra("path");
        if (path == null) { finish(); return; }
        storedFile = new File(path);
        videoMode = getIntent().getBooleanExtra("video", false);
        storage = new VaultStorageManager(this);
        storage.clearStalePreviewFiles();

        findViewById(R.id.imageActions).setVisibility(videoMode ? View.GONE : View.VISIBLE);
        rotateButton = findViewById(R.id.rotateButton);
        deleteButton = findViewById(R.id.deleteButton);
        shareButton = findViewById(R.id.shareButton);
        rotateButton.setOnClickListener(v -> rotateImage());
        deleteButton.setOnClickListener(v -> confirmDelete());
        shareButton.setOnClickListener(v -> shareImage());

        worker.execute(() -> {
            File decoded = null;
            try {
                decoded = storage.createDecryptedCacheFile(storedFile);
                if (videoMode) {
                    File videoFile = decoded;
                    runOnUiThread(() -> showVideo(videoFile));
                } else {
                    Bitmap bitmap = decodePreview(decoded);
                    File preview = decoded.equals(storedFile) ? null : decoded;
                    runOnUiThread(() -> showImage(bitmap, preview));
                }
            } catch (Exception e) {
                if (decoded != null && !decoded.equals(storedFile)) decoded.delete();
                runOnUiThread(() -> showError("Could not open this file. It may be damaged or unsupported."));
            } catch (OutOfMemoryError e) {
                if (decoded != null && !decoded.equals(storedFile)) decoded.delete();
                runOnUiThread(() -> showError("This image is too large to display on this device."));
            }
        });
    }

    private Bitmap decodePreview(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Unsupported or invalid image");
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int targetWidth = Math.max(1, Math.min(metrics.widthPixels, MAX_IMAGE_EDGE));
        int targetHeight = Math.max(1, Math.min(metrics.heightPixels, MAX_IMAGE_EDGE));
        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > targetWidth
                || bounds.outHeight / sampleSize > targetHeight
                || bounds.outWidth / sampleSize > MAX_IMAGE_EDGE
                || bounds.outHeight / sampleSize > MAX_IMAGE_EDGE) sampleSize *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError firstFailure) {
            options.inSampleSize = Math.max(2, sampleSize * 2);
            try { bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options); }
            catch (OutOfMemoryError secondFailure) { throw new IOException("Not enough memory to display image", secondFailure); }
        }
        if (bitmap == null) throw new IOException("Image decoder returned no bitmap");
        return bitmap;
    }

    private void showImage(Bitmap bitmap, File preview) {
        if (isFinishing() || isDestroyed()) {
            bitmap.recycle();
            if (preview != null) preview.delete();
            return;
        }
        try {
            ImageView image = findViewById(R.id.image);
            image.setVisibility(View.VISIBLE);
            image.setImageBitmap(bitmap);
            imageBitmap = bitmap;
            previewFile = preview;
        } catch (RuntimeException | OutOfMemoryError error) {
            bitmap.recycle();
            if (preview != null) preview.delete();
            showError("Could not display this image.");
        }
    }

    private void rotateImage() {
        if (videoMode || storedFile == null) return;
        setActionsEnabled(false);
        worker.execute(() -> {
            File source = null;
            File rotatedTemp = null;
            File freshPreview = null;
            Bitmap original = null;
            Bitmap rotated = null;
            try {
                source = storage.createDecryptedCacheFile(storedFile);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                original = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
                if (original == null) throw new IOException("Unsupported or invalid image");
                Matrix matrix = new Matrix();
                matrix.postRotate(90f);
                rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
                if (rotated != original) {
                    original.recycle();
                    original = null;
                }
                Bitmap.CompressFormat format = compressionFormat(storedFile);
                rotatedTemp = File.createTempFile("vault-rotated-", ".tmp", getCacheDir());
                try (FileOutputStream output = new FileOutputStream(rotatedTemp)) {
                    if (!rotated.compress(format, format == Bitmap.CompressFormat.JPEG ? 95 : 100, output)) {
                        throw new IOException("Could not encode rotated image");
                    }
                    output.flush();
                }
                storage.replaceEncryptedImage(storedFile, rotatedTemp);
                freshPreview = storage.createDecryptedCacheFile(storedFile);
                Bitmap previewBitmap = decodePreview(freshPreview);
                File readyPreview = freshPreview.equals(storedFile) ? null : freshPreview;
                freshPreview = null;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        previewBitmap.recycle();
                        if (readyPreview != null) readyPreview.delete();
                        return;
                    }
                    clearCurrentImage();
                    showImage(previewBitmap, readyPreview);
                    setActionsEnabled(true);
                    Toast.makeText(this, "Image rotated and saved", Toast.LENGTH_SHORT).show();
                });
            } catch (OutOfMemoryError error) {
                runOnUiThread(() -> rotationFailed("Not enough memory to rotate this image."));
            } catch (Exception error) {
                runOnUiThread(() -> rotationFailed("Could not rotate this image. Try a JPEG, PNG, or WebP image."));
            } finally {
                if (original != null && !original.isRecycled()) original.recycle();
                if (rotated != null && rotated != original && !rotated.isRecycled()) rotated.recycle();
                if (source != null && !source.equals(storedFile)) source.delete();
                if (rotatedTemp != null) rotatedTemp.delete();
                if (freshPreview != null && !freshPreview.equals(storedFile)) freshPreview.delete();
            }
        });
    }

    private Bitmap.CompressFormat compressionFormat(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(VaultStorageManager.ENCRYPTED_SUFFIX)) {
            name = name.substring(0, name.length() - VaultStorageManager.ENCRYPTED_SUFFIX.length());
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return Bitmap.CompressFormat.JPEG;
        if (name.endsWith(".png")) return Bitmap.CompressFormat.PNG;
        if (name.endsWith(".webp")) return Bitmap.CompressFormat.WEBP;
        throw new IOException("Unsupported image format for rotation");
    }

    private void clearCurrentImage() {
        ImageView image = findViewById(R.id.image);
        if (image != null) image.setImageDrawable(null);
        if (imageBitmap != null && !imageBitmap.isRecycled()) imageBitmap.recycle();
        imageBitmap = null;
        if (previewFile != null && previewFile.exists()) previewFile.delete();
        previewFile = null;
    }

    private void rotationFailed(String message) {
        if (isFinishing() || isDestroyed()) return;
        setActionsEnabled(true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete image?")
                .setMessage("This permanently removes the image from your vault.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> deleteImage())
                .show();
    }

    private void deleteImage() {
        setActionsEnabled(false);
        worker.execute(() -> {
            try {
                boolean deleted = storage.deleteVaultFile(storedFile);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (deleted) {
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        setActionsEnabled(true);
                        Toast.makeText(this, "Could not delete this image", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setActionsEnabled(true);
                    Toast.makeText(this, "Could not delete this image", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void shareImage() {
        if (storedFile == null) return;
        setActionsEnabled(false);
        worker.execute(() -> {
            File shareFile = null;
            try {
                shareFile = storage.createShareCopy(storedFile);
                File readyFile = shareFile;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        readyFile.delete();
                        return;
                    }
                    try {
                        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", readyFile);
                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType(mimeTypeFor(readyFile));
                        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                        shareIntent.setClipData(ClipData.newUri(getContentResolver(), "Shared image", uri));
                        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(shareIntent, "Share image"));
                    } catch (RuntimeException error) {
                        readyFile.delete();
                        Toast.makeText(this, "Could not share this image", Toast.LENGTH_LONG).show();
                    } finally {
                        setActionsEnabled(true);
                    }
                });
            } catch (Exception error) {
                if (shareFile != null) shareFile.delete();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setActionsEnabled(true);
                    Toast.makeText(this, "Could not prepare this image for sharing", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String mimeTypeFor(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        return "image/*";
    }

    private void setActionsEnabled(boolean enabled) {
        if (rotateButton != null) rotateButton.setEnabled(enabled);
        if (deleteButton != null) deleteButton.setEnabled(enabled);
        if (shareButton != null) shareButton.setEnabled(enabled);
    }

    private void showVideo(File file) {
        if (isFinishing() || isDestroyed()) {
            if (!file.getAbsolutePath().startsWith(getFilesDir().getAbsolutePath())) file.delete();
            return;
        }
        try {
            previewFile = file.getAbsolutePath().startsWith(getFilesDir().getAbsolutePath()) ? null : file;
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            VideoView player = findViewById(R.id.video);
            player.setVisibility(View.VISIBLE);
            player.setMediaController(new MediaController(this));
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "Cannot play this video", Toast.LENGTH_LONG).show();
                return true;
            });
            player.setOnPreparedListener(mp -> player.start());
            player.setVideoURI(uri);
        } catch (RuntimeException error) {
            showError("Could not open this video.");
        }
    }

    private void showError(String message) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        clearCurrentImage();
        if (previewFile != null && previewFile.exists()) previewFile.delete();
        super.onDestroy();
    }
}
