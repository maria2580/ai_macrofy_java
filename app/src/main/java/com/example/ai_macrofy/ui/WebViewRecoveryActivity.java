package com.example.ai_macrofy.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.ai_macrofy.R;

public class WebViewRecoveryActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview_recovery);
        setTitle("WebView Error");

        Button openSettingsButton = findViewById(R.id.button_open_webview_settings);
        Button closeButton = findViewById(R.id.button_close);

        openSettingsButton.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                String webViewPackage = "com.google.android.webview"; // From error logs
                
                // On modern Android, we can get the package name dynamically
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PackageInfo packageInfo = WebView.getCurrentWebViewPackage();
                    if (packageInfo != null) {
                        webViewPackage = packageInfo.packageName;
                    }
                }
                
                Uri uri = Uri.fromParts("package", webViewPackage, null);
                intent.setData(uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Could not open WebView settings automatically.", Toast.LENGTH_LONG).show();
            }
            finish();
        });

        closeButton.setOnClickListener(v -> finish());
    }
} 