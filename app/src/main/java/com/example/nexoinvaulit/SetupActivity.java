package com.example.nexoinvaulit;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SetupActivity extends AppCompatActivity {
    private EditText password;
    private EditText confirmPassword;
    private Button actionButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (PasswordUtils.isSet(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_password);
        ((TextView) findViewById(R.id.title)).setText("Create a 6-digit passcode");
        password = findViewById(R.id.password);
        confirmPassword = findViewById(R.id.confirm);
        actionButton = findViewById(R.id.action);
        actionButton.setText("Create passcode");
        actionButton.setOnClickListener(view -> createPassword());
    }

    private void createPassword() {
        String value = password.getText().toString();
        if (!value.matches("[0-9]{6}")) {
            password.setError("Choose exactly 6 digits");
            return;
        }
        if (!value.equals(confirmPassword.getText().toString())) {
            confirmPassword.setError("Passcodes do not match");
            return;
        }
        actionButton.setEnabled(false);
        new Thread(() -> {
            try {
                PasswordUtils.setPassword(this, value);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, MainActivity.class));
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Could not save passcode and vault key", Toast.LENGTH_LONG).show();
                    actionButton.setEnabled(true);
                });
            }
        }, "vault-key-setup").start();
    }
}
