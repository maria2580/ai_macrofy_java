package com.example.ai_macrofy.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.ai_macrofy.services.foreground.MyForegroundService;

public class PermissionRequestActivity extends AppCompatActivity {

    private static final String TAG = "PermissionRequestActivity";
    private ActivityResultLauncher<Intent> mediaProjectionLauncher;
    private Intent serviceIntentData;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Store the intent that started this activity, it contains all the data for the service.
        serviceIntentData = getIntent();

        mediaProjectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Log.d(TAG, "MediaProjection permission granted. Starting service.");
                        // Create a new intent for the service and copy extras.
                        Intent serviceIntent = new Intent(this, MyForegroundService.class);
                        if (serviceIntentData != null && serviceIntentData.getExtras() != null) {
                            serviceIntent.putExtras(serviceIntentData.getExtras());
                        }
                        // Add the media projection data.
                        serviceIntent.putExtra("media_projection_result_code", result.getResultCode());
                        serviceIntent.putExtra("media_projection_result_data", result.getData());

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent);
                        } else {
                            startService(serviceIntent);
                        }
                    } else {
                        Log.w(TAG, "MediaProjection permission denied.");
                        Toast.makeText(this, "Screen capture permission is required to start the macro.", Toast.LENGTH_LONG).show();
                    }
                    finish();
                }
        );

        // Launch the permission request
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent());
    }
}
