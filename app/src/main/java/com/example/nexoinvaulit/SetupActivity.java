package com.example.nexoinvaulit;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SetupActivity extends AppCompatActivity {
    private EditText password, confirmPassword;
    private Button actionButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (PasswordUtils.isSet(this)) {
            startActivity(new Intent(this, LoginActivity.class)); finish(); return;
        }
        setContentView(R.layout.activity_password);
        ((TextView) findViewById(R.id.title)).setText("Create app password");
        password = findViewById(R.id.password);
        confirmPassword = findViewById(R.id.confirm);
        actionButton = findViewById(R.id.action);
        actionButton.setText("Create password");
        actionButton.setOnClickListener(v -> createPassword());
    }

    private void createPassword() {
        String value = password.getText().toString();
        if (value.length() < 6) { password.setError("Use at least 6 characters"); return; }
        if (!value.equals(confirmPassword.getText().toString())) { confirmPassword.setError("Passwords do not match"); return; }
        actionButton.setEnabled(false);
        new Thread(() -> {
            try {
                PasswordUtils.setPassword(this, value);
                runOnUiThread(() -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
            } catch (Exception e) {
                runOnUiThread(() -> { Toast.makeText(this, "Could not save password and vault key", Toast.LENGTH_LONG).show(); actionButton.setEnabled(true); });
            }
        }, "vault-key-setup").start();
    }
}
