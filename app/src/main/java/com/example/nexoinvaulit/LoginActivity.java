package com.example.nexoinvaulit;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!PasswordUtils.isSet(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_login);
        EditText password = findViewById(R.id.password);
        findViewById(R.id.login).setOnClickListener(v -> {
            String value = password.getText().toString();
            if (value.isEmpty()) { password.setError("Enter your password"); return; }
            v.setEnabled(false);
            worker.execute(() -> {
                try {
                    boolean unlocked = PasswordUtils.unlock(this, value);
                    if (!unlocked) {
                        runOnUiThread(() -> { password.setError("Wrong password"); v.setEnabled(true); });
                        return;
                    }
                    int migrated = 0;
                    String migrationWarning = null;
                    try { migrated = new VaultStorageManager(this).migrateLegacyFiles(); }
                    catch (Exception migrationError) { migrationWarning = "Some older files could not be encrypted. Keep this app data safe and try again."; }
                    final int migratedCount = migrated;
                    final String warning = migrationWarning;
                    runOnUiThread(() -> {
                        if (warning != null) Toast.makeText(this, warning, Toast.LENGTH_LONG).show();
                        else if (migratedCount > 0) Toast.makeText(this, migratedCount + " older file(s) encrypted", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    });
                } catch (Exception error) {
                    VaultSession.lock();
                    runOnUiThread(() -> { password.setError("Could not unlock vault"); v.setEnabled(true); });
                }
            });
        });
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
