package com.example.nexoinvaulit;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!VaultSession.isUnlocked()) {
            startActivity(new Intent(this, LoginActivity.class)); finish(); return;
        }
        setContentView(R.layout.activity_main);
        findViewById(R.id.imagesFolder).setOnClickListener(v -> open("Images"));
        findViewById(R.id.videosFolder).setOnClickListener(v -> open("Videos"));
        findViewById(R.id.changePassword).setOnClickListener(v -> startActivity(new Intent(this, ChangePasswordActivity.class)));
    }

    private void open(String name) {
        Intent intent = new Intent(this, folder.class);
        intent.putExtra("folder", name);
        startActivity(intent);
    }
}
