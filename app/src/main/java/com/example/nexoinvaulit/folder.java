package com.example.nexoinvaulit;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class folder extends AppCompatActivity {
    private static final int PICK = 10;
    private String folderName;
    private LinearLayout list;
    private boolean videoMode;
    private VaultStorageManager storage;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_folder);
        storage = new VaultStorageManager(this);
        folderName = getIntent().getStringExtra("folder");
        if (!"Images".equals(folderName) && !"Videos".equals(folderName)) { finish(); return; }
        ((TextView) findViewById(R.id.title)).setText(folderName);
        list = findViewById(R.id.list);
        videoMode = "Videos".equals(folderName);
        findViewById(R.id.importButton).setOnClickListener(v -> pick());
        findViewById(R.id.pasteButton).setOnClickListener(v -> paste());
    }

    private File dir() { return storage.getFolder(folderName); }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType(videoMode ? "video/*" : "image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK);
    }

    private void paste() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show(); return;
        }
        ClipData clip = manager.getPrimaryClip();
        if (clip == null) return;
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null) importUri(uri);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) importUri(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) importUri(data.getData());
    }

    private void importUri(Uri uri) {
        String name = displayName(uri);
        worker.execute(() -> {
            try {
                File saved = storage.importUriToFolder(uri, dir(), name);
                runOnUiThread(() -> { Toast.makeText(this, "Encrypted file saved", Toast.LENGTH_SHORT).show(); load(); });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not securely save file", Toast.LENGTH_LONG).show());
            }
        });
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "file-" + System.currentTimeMillis();
    }

    private void load() {
        if (list == null) return;
        list.removeAllViews();
        File[] files = dir().listFiles(file -> file.isFile() && !file.getName().endsWith(".tmp") && !file.getName().startsWith("."));
        if (files == null || files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("No files yet. Import an image or video.");
            empty.setPadding(16, 32, 16, 16);
            list.addView(empty); return;
        }
        for (File file : files) {
            Button row = new Button(this);
            String visibleName = file.getName().endsWith(VaultStorageManager.ENCRYPTED_SUFFIX)
                    ? file.getName().substring(0, file.getName().length() - VaultStorageManager.ENCRYPTED_SUFFIX.length()) : file.getName();
            row.setText(visibleName);
            row.setAllCaps(false);
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, ViewerActivity.class);
                intent.putExtra("path", file.getAbsolutePath());
                intent.putExtra("video", videoMode);
                startActivity(intent);
            });
            list.addView(row);
        }
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
