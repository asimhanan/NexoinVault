package com.example.nexoinvaulit;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {
    private static final int PASSCODE_LENGTH = 6;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder passcode = new StringBuilder(PASSCODE_LENGTH);
    private final int[] digitKeyIds = {
            R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
            R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9
    };
    private final int[] passcodeDotIds = {
            R.id.passcodeDot1, R.id.passcodeDot2, R.id.passcodeDot3,
            R.id.passcodeDot4, R.id.passcodeDot5, R.id.passcodeDot6
    };

    private View keypad;
    private View passcodeDots;
    private View legacyPanel;
    private TextView passcodeHint;
    private TextView legacyLink;
    private TextView statusMessage;
    private EditText legacyPassword;
    private Button legacyUnlock;
    private boolean legacyMode;
    private boolean unlocking;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!PasswordUtils.isSet(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        // Never leave a previously held in-memory key available on the lock screen.
        VaultSession.lock();
        setContentView(R.layout.activity_login);

        keypad = findViewById(R.id.keypad);
        passcodeDots = findViewById(R.id.passcodeDots);
        legacyPanel = findViewById(R.id.legacyPanel);
        passcodeHint = findViewById(R.id.passcodeHint);
        legacyLink = findViewById(R.id.legacyLink);
        statusMessage = findViewById(R.id.statusMessage);
        legacyPassword = findViewById(R.id.legacyPassword);
        legacyUnlock = findViewById(R.id.legacyUnlock);

        for (int digit = 0; digit <= 9; digit++) {
            final int selectedDigit = digit;
            findViewById(digitKeyIds[digit]).setOnClickListener(v -> appendDigit(selectedDigit));
        }
        findViewById(R.id.deleteKey).setOnClickListener(v -> deleteLastDigit());
        legacyLink.setOnClickListener(v -> toggleEntryMode());
        legacyUnlock.setOnClickListener(v -> unlockWithLegacyPassword());
        legacyPassword.setOnEditorActionListener((text, actionId, event) -> {
            unlockWithLegacyPassword();
            return true;
        });
        updatePasscodeDots();
    }

    private void appendDigit(int digit) {
        if (unlocking || legacyMode || passcode.length() >= PASSCODE_LENGTH) return;
        passcode.append((char) ('0' + digit));
        clearStatus();
        updatePasscodeDots();
        if (passcode.length() == PASSCODE_LENGTH) {
            // Verify immediately after the sixth digit; no extra Unlock tap is needed.
            unlock(passcode.toString(), false);
        }
    }

    private void deleteLastDigit() {
        if (unlocking || legacyMode || passcode.length() == 0) return;
        passcode.deleteCharAt(passcode.length() - 1);
        clearStatus();
        updatePasscodeDots();
    }

    private void updatePasscodeDots() {
        for (int index = 0; index < passcodeDotIds.length; index++) {
            View dot = findViewById(passcodeDotIds[index]);
            dot.setBackgroundResource(index < passcode.length()
                    ? R.drawable.passcode_dot_filled
                    : R.drawable.passcode_dot_empty);
        }
        passcodeDots.setContentDescription(passcode.length() + " of " + PASSCODE_LENGTH + " digits entered");
    }

    private void toggleEntryMode() {
        if (unlocking) return;
        legacyMode = !legacyMode;
        clearStatus();
        if (legacyMode) {
            passcode.setLength(0);
            updatePasscodeDots();
            passcodeDots.setVisibility(View.GONE);
            keypad.setVisibility(View.GONE);
            legacyPanel.setVisibility(View.VISIBLE);
            passcodeHint.setText("Enter your saved password");
            legacyLink.setText("Use numeric passcode instead");
            legacyPassword.requestFocus();
            legacyPassword.post(() -> {
                InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (manager != null) manager.showSoftInput(legacyPassword, InputMethodManager.SHOW_IMPLICIT);
            });
        } else {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.hideSoftInputFromWindow(legacyPassword.getWindowToken(), 0);
            legacyPassword.setText("");
            legacyPanel.setVisibility(View.GONE);
            passcodeDots.setVisibility(View.VISIBLE);
            keypad.setVisibility(View.VISIBLE);
            passcodeHint.setText("Enter your 6-digit passcode");
            legacyLink.setText("Use full password instead");
        }
    }

    private void unlockWithLegacyPassword() {
        String value = legacyPassword.getText().toString();
        if (value.isEmpty()) {
            legacyPassword.setError("Enter your saved password");
            return;
        }
        unlock(value, true);
    }

    private void unlock(String value, boolean fromLegacyPassword) {
        if (unlocking || value.isEmpty()) return;
        unlocking = true;
        clearStatus();
        statusMessage.setText("Checking passcode…");
        statusMessage.setVisibility(View.VISIBLE);
        setControlsEnabled(false);

        worker.execute(() -> {
            try {
                boolean unlocked = PasswordUtils.unlock(this, value);
                if (!unlocked) {
                    runOnUiThread(() -> {
                        unlocking = false;
                        if (fromLegacyPassword) {
                            legacyPassword.setError("Password is not correct");
                        } else {
                            passcode.setLength(0);
                            updatePasscodeDots();
                        }
                        statusMessage.setText(fromLegacyPassword
                                ? "That password did not match. Try again."
                                : "Incorrect passcode. Try again.");
                        statusMessage.setVisibility(View.VISIBLE);
                        setControlsEnabled(true);
                    });
                    return;
                }

                int migrated = 0;
                String migrationWarning = null;
                try {
                    migrated = new VaultStorageManager(this).migrateLegacyFiles();
                } catch (Exception migrationError) {
                    migrationWarning = "Some older files could not be encrypted. Keep this app data safe and try again.";
                }
                final int migratedCount = migrated;
                final String warning = migrationWarning;
                runOnUiThread(() -> {
                    if (warning != null) Toast.makeText(this, warning, Toast.LENGTH_LONG).show();
                    else if (migratedCount > 0) {
                        Toast.makeText(this, migratedCount + " older file(s) encrypted", Toast.LENGTH_SHORT).show();
                    }
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception error) {
                VaultSession.lock();
                runOnUiThread(() -> {
                    unlocking = false;
                    if (!fromLegacyPassword) {
                        passcode.setLength(0);
                        updatePasscodeDots();
                    }
                    statusMessage.setText("Could not unlock the vault. Try again.");
                    statusMessage.setVisibility(View.VISIBLE);
                    setControlsEnabled(true);
                });
            }
        });
    }

    private void setControlsEnabled(boolean enabled) {
        for (int id : digitKeyIds) findViewById(id).setEnabled(enabled);
        findViewById(R.id.deleteKey).setEnabled(enabled);
        legacyLink.setEnabled(enabled);
        legacyPassword.setEnabled(enabled);
        legacyUnlock.setEnabled(enabled);
    }

    private void clearStatus() {
        statusMessage.setText("");
        statusMessage.setVisibility(View.GONE);
        legacyPassword.setError(null);
    }

    @Override protected void onDestroy() {
        passcode.setLength(0);
        if (legacyPassword != null) legacyPassword.setText("");
        worker.shutdownNow();
        super.onDestroy();
    }
}
