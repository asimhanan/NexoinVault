package com.example.nexoinvaulit;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChangePasswordActivity extends AppCompatActivity {
    private EditText oldPassword;
    private EditText newPassword;
    private EditText confirmPassword;
    private Button actionButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_password);
        ((TextView) findViewById(R.id.title)).setText("Change passcode");
        oldPassword = findViewById(R.id.oldPassword);
        newPassword = findViewById(R.id.password);
        confirmPassword = findViewById(R.id.confirm);
        oldPassword.setHint("Current password or passcode");
        newPassword.setHint("New 6-digit passcode");
        confirmPassword.setHint("Confirm new passcode");
        actionButton = findViewById(R.id.action);
        actionButton.setText("Save new passcode");
        oldPassword.setVisibility(View.VISIBLE);
        actionButton.setOnClickListener(v -> changePassword());
    }

    private void changePassword() {
        String oldValue = oldPassword.getText().toString();
        String newValue = newPassword.getText().toString();
        if (oldValue.isEmpty()) {
            oldPassword.setError("Enter your current password");
            return;
        }
        if (!newValue.matches("[0-9]{6}")) {
            newPassword.setError("Choose exactly 6 digits");
            return;
        }
        if (!newValue.equals(confirmPassword.getText().toString())) {
            confirmPassword.setError("Passcodes do not match");
            return;
        }
        actionButton.setEnabled(false);
        worker.execute(() -> {
            try {
                PasswordUtils.changePassword(this, oldValue, newValue);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Passcode changed successfully", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (SecurityException wrong) {
                runOnUiThread(() -> {
                    oldPassword.setError("Current password is incorrect");
                    actionButton.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Could not change passcode", Toast.LENGTH_LONG).show();
                    actionButton.setEnabled(true);
                });
            }
        });
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
