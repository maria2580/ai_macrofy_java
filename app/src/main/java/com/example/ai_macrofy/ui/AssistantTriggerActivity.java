package com.example.ai_macrofy.ui;

import android.Manifest;
import android.app.assist.AssistContent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.Pair;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.ai_macrofy.services.foreground.MyForegroundService;
import com.example.ai_macrofy.utils.AppPreferences;
import android.app.PendingIntent;
import android.text.TextUtils;
import com.example.ai_macrofy.utils.PromptManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AssistantTriggerActivity extends AppCompatActivity {

    private static final String TAG = "AssistantTriggerActivity";
    public static final String ACTION_EXECUTE_MACRO = "com.example.ai_macrofy.action.EXECUTE_MACRO";
    public static final String EXTRA_USER_COMMAND = "com.example.ai_macrofy.extra.USER_COMMAND";
    private static final int SPEECH_REQUEST_CODE = 124; // Different from MainActivity's code

    private AppPreferences appPreferences;
    private int screenWidth;
    private int screenHeight;
    private String userCommandForMacro; // To hold the command while waiting for permission

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get screen dimensions
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
        android.graphics.Rect bounds = windowMetrics.getBounds();
        screenWidth = bounds.width();
        screenHeight = bounds.height();

        appPreferences = new AppPreferences(this);

        Intent intent = getIntent();
        if (intent != null) {
            // Check for a pending command from MainActivity first
            String pendingCommand = appPreferences.getAndClearPendingCommand();
            if (pendingCommand != null) {
                Log.d(TAG, "Found pending command from MainActivity: " + pendingCommand);
                // We have the command, now start the macro service
                handleAssist(pendingCommand);
                return; // Skip other intent actions
            }

            if (ACTION_EXECUTE_MACRO.equals(intent.getAction())) {
                String userCommand = intent.getStringExtra(EXTRA_USER_COMMAND);
                if (userCommand != null && !userCommand.isEmpty()) {
                    Log.d(TAG, "Received command from existing macro action: " + userCommand);
                    handleAssist(userCommand);
                } else {
                    Log.e(TAG, "No user command provided in the intent.");
                    Toast.makeText(this, "Error: No command received.", Toast.LENGTH_SHORT).show();
                    finish();
                }
            } else if (Intent.ACTION_ASSIST.equals(intent.getAction())) {
                Log.d(TAG, "Triggered by Assistant action. Starting voice recognition.");
                // onProvideAssistContent will be called, but we start speech recognition first
                startSpeechToText();
            } else {
                Log.e(TAG, "Activity started with an unsupported action: " + intent.getAction());
                finish();
            }
        } else {
            Log.e(TAG, "Activity started with a null intent.");
            finish();
        }
    }

    // This method is called when this activity is the active assistant and is invoked.
    @Override
    public void onProvideAssistContent(AssistContent outContent) {
        super.onProvideAssistContent(outContent);
        Log.d(TAG, "onProvideAssistContent called. This is where we could add more context if needed.");
        // We don't need to add anything here, as we get the content in onActivityResult from the voice session.
    }

    private void startSpeechToText() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Audio recording permission is required for the assistant feature. Please grant it from the main app.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...");
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE);
        } catch (ActivityNotFoundException a) {
            Toast.makeText(getApplicationContext(), "Speech recognition not supported on this device.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                String recognizedText = result.get(0);
                Log.d(TAG, "Recognized text: " + recognizedText);
                handleAssist(recognizedText);
            } else {
                Toast.makeText(this, "Could not recognize speech.", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else {
            // If speech recognition was cancelled or failed, just finish the activity.
            Log.d(TAG, "Speech recognition cancelled or failed.");
            finish();
        }
    }

    private void handleAssist(String command) {
        // Store command and start the service directly.
        this.userCommandForMacro = command;
        Log.d(TAG, "Starting service for command: " + command);
        startMacroService();
    }

    private void startMacroService() {
        // 이제 앱 목록을 미리 가져올 필요가 없으므로 스레드 제거
        startMacro(userCommandForMacro);
    }

    private String getLaunchableApplicationsListString() {
        List<Pair<String, String>> launchableApps = getLaunchableApplications();

        if (launchableApps.isEmpty()) {
            return "App list is not available yet. Please try again in a moment.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("List of launchable applications (Format: Package Name : Display Name):\n");
        for (Pair<String, String> appEntry : launchableApps) {
            sb.append("- ").append(appEntry.first).append(" : ").append(appEntry.second).append("\n");
        }
        return sb.toString();
    }

    private List<Pair<String, String>> getLaunchableApplications() {
        Map<String, String> appMap = appPreferences.getAppList();
        List<Pair<String, String>> launchableApps = new ArrayList<>();
        for (Map.Entry<String, String> entry : appMap.entrySet()) {
            launchableApps.add(new Pair<>(entry.getKey(), entry.getValue()));
        }
        // 이름순으로 정렬
        Collections.sort(launchableApps, Comparator.comparing(o -> o.second.toLowerCase()));
        return launchableApps;
    }

    private String getFilteredAppsListForPrompt(String command, List<Pair<String, String>> allApps) {
        List<Pair<String, String>> relevantApps = new ArrayList<>();
        String lowerCaseCommand = command.toLowerCase();

        for (Pair<String, String> app : allApps) {
            if (lowerCaseCommand.contains(app.second.toLowerCase())) {
                relevantApps.add(app);
            }
        }

        if (!relevantApps.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("List of relevant applications (Format: Package Name : Display Name):\n");
            for (Pair<String, String> appEntry : relevantApps) {
                sb.append("- ").append(appEntry.first).append(" : ").append(appEntry.second).append("\n");
            }
            return sb.toString();
        }

        // If no specific app is mentioned, don't send the full list to save tokens.
        // The model should infer actions based on the screen content.
        return "No specific application mentioned in the command. Analyze the screen to perform actions.\n";
    }

    private String getSimplifiedLaunchableApplicationsListString() {
        LauncherApps launcherApps = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        if (launcherApps == null) {
            return "Could not access LauncherApps service.\n";
        }

        Set<String> appNames = new HashSet<>();
        List<UserHandle> profiles = launcherApps.getProfiles();
        for (UserHandle profile : profiles) {
            List<LauncherActivityInfo> apps = launcherApps.getActivityList(null, profile);
            for (LauncherActivityInfo app : apps) {
                appNames.add(app.getLabel().toString());
            }
        }

        if (appNames.isEmpty()) {
            return "No applications found.\n";
        }

        List<String> sortedAppNames = new ArrayList<>(appNames);
        Collections.sort(sortedAppNames, String.CASE_INSENSITIVE_ORDER);

        return "Available apps: " + TextUtils.join(", ", sortedAppNames) + "\n";
    }


    private void startMacro(String recognizedCommand) {
        String currentProvider = appPreferences.getAiProvider();
        String apiKey = appPreferences.getApiKeyForCurrentProvider();

        if (apiKey.isEmpty() && !AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentProvider) && !AppPreferences.PROVIDER_GEMINI_WEB.equals(currentProvider)) {
            String providerName;
            if (AppPreferences.PROVIDER_OPENAI.equals(currentProvider)) {
                providerName = "OpenAI";
            } else if (AppPreferences.PROVIDER_GEMINI.equals(currentProvider)) {
                providerName = "Gemini";
            } else {
                providerName = "Selected Provider";
            }
            Log.e(TAG, "API Key for " + providerName + " is not set.");
            Toast.makeText(this, "API Key for " + providerName + " is not set. Please set it in the app.", Toast.LENGTH_LONG).show();
            finish(); // Finish activity if API key is missing
            return;
        }

        String finalSystemPrompt;
        String appsListForPrompt;
        List<Pair<String, String>> allApps = getLaunchableApplications();

        if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentProvider)) {
            appsListForPrompt = getFilteredAppsListForPrompt(recognizedCommand, allApps);
        } else {
            appsListForPrompt = getLaunchableApplicationsListString();
        }
        finalSystemPrompt = PromptManager.getSystemPrompt(currentProvider, screenWidth, screenHeight, appsListForPrompt);

        Log.d(TAG, "Starting MyForegroundService with command: " + recognizedCommand);
        Toast.makeText(this, "Starting Ai_macrofy command...", Toast.LENGTH_SHORT).show();

        Intent serviceIntent = new Intent(this, PermissionRequestActivity.class);
        serviceIntent.putExtra("apiKey", apiKey);
        serviceIntent.putExtra("baseSystemPrompt", finalSystemPrompt);
        serviceIntent.putExtra("userCommand", recognizedCommand);
        serviceIntent.putExtra("ai_provider", currentProvider);
        // MediaProjection data is no longer passed from here.


        startActivity(serviceIntent);

        // The activity is finished after starting the service.
        finish();
    }
}