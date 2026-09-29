package com.example.nexoinvaulit;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ViewerActivity extends AppCompatActivity {
    // Decode no more than a 2048px edge for an on-screen preview. This prevents
    // large camera images from being fully decoded into memory just to be scaled
    // down by ImageView afterwards.
    private static final int MAX_IMAGE_EDGE = 2048;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File previewFile;
    private Bitmap imageBitmap;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_viewer);

        String path = getIntent().getStringExtra("path");
        if (path == null) { finish(); return; }
        boolean video = getIntent().getBooleanExtra("video", false);
        VaultStorageManager storage = new VaultStorageManager(this);
        storage.clearStalePreviewFiles();

        worker.execute(() -> {
            File decoded = null;
            try {
                File stored = new File(path);
                decoded = storage.createDecryptedCacheFile(stored);
                if (video) {
                    File videoFile = decoded;
                    runOnUiThread(() -> showVideo(videoFile));
                } else {
                    Bitmap bitmap = decodePreview(decoded);
                    File preview = decoded.equals(stored) ? null : decoded;
                    runOnUiThread(() -> showImage(bitmap, preview));
                }
            } catch (Exception e) {
                if (decoded != null && !decoded.equals(new File(path))) decoded.delete();
                runOnUiThread(() -> showError("Could not open this file. It may be damaged or unsupported."));
            }
        });
    }

    private Bitmap decodePreview(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Unsupported or invalid image");
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int targetWidth = Math.max(1, Math.min(metrics.widthPixels, MAX_IMAGE_EDGE));
        int targetHeight = Math.max(1, Math.min(metrics.heightPixels, MAX_IMAGE_EDGE));
        int sampleSize = 1;
        while (bounds.outWidth / sampleSize > targetWidth
                || bounds.outHeight / sampleSize > targetHeight
                || bounds.outWidth / sampleSize > MAX_IMAGE_EDGE
                || bounds.outHeight / sampleSize > MAX_IMAGE_EDGE) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError firstFailure) {
            // Retry once at a lower resolution instead of letting an unusually
            // memory-constrained device terminate the whole app.
            options.inSampleSize = Math.max(2, sampleSize * 2);
            try {
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (OutOfMemoryError secondFailure) {
                throw new IOException("Not enough memory to display image", secondFailure);
            }
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
        if (imageBitmap != null) {
            ImageView image = findViewById(R.id.image);
            if (image != null) image.setImageDrawable(null);
            if (!imageBitmap.isRecycled()) imageBitmap.recycle();
            imageBitmap = null;
        }
        if (previewFile != null && previewFile.exists()) previewFile.delete();
        super.onDestroy();
    }
}
