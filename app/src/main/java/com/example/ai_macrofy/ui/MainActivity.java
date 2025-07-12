package com.example.ai_macrofy.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.llm.gemma.GemmaManager;
import com.example.ai_macrofy.llm.gemma.InitializationCallback;
import com.example.ai_macrofy.services.accessibility.LayoutAccessibilityService;
import com.example.ai_macrofy.services.accessibility.MacroAccessibilityService;
import com.example.ai_macrofy.services.downloader.ModelDownloadService;
import com.example.ai_macrofy.services.foreground.MyForegroundService;
import com.example.ai_macrofy.utils.AppPreferences;
import com.example.ai_macrofy.utils.ModelDownloader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final int SPEECH_REQUEST_CODE = 123;
    private static final int REQUEST_POST_NOTIFICATIONS_PERMISSION = 300;
    private static final int REQUEST_MEDIA_PROJECTION = 400;


    private TextView textViewResult;
    private EditText textViewRecognizedPrompt;
    private TextView textViewCurrentApiKeyStatus;

    private AppPreferences appPreferences;
    private String currentRecognizedText = "";
    int screenWidth;
    int screenHeight;
    private AlertDialog activeDialog = null;
    private AlertDialog progressDialog = null;
    private BroadcastReceiver downloadProgressReceiver;
    private MediaProjectionManager mediaProjectionManager;

    // private ActivityResultLauncher<Intent> mediaProjectionLauncher; // No longer needed here


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
        android.graphics.Rect bounds = windowMetrics.getBounds();
        screenWidth = bounds.width();
        screenHeight = bounds.height();
        Log.d("mainActivity", "onCreate: screenWidth: "+screenWidth+","+screenHeight);


        appPreferences = new AppPreferences(this);
        // mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE); // Not needed at init
        // mediaProjectionLauncher is no longer initialized here
        textViewResult = findViewById(R.id.textView_result);
        textViewRecognizedPrompt = findViewById(R.id.textView_recognized_prompt);
        Button buttonRecordPrompt = findViewById(R.id.button_record_prompt);
        Button buttonSettings = findViewById(R.id.button_settings);
        textViewCurrentApiKeyStatus = findViewById(R.id.textView_current_api_key_status);

        Button buttonStartMacro = findViewById(R.id.button_start_macro);
        Button buttonStopMacro = findViewById(R.id.button_stop_macro);

        buttonRecordPrompt.setOnClickListener(v -> startSpeechToText());
        buttonSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));


        buttonStartMacro.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled(MacroAccessibilityService.class)) {
                Toast.makeText(this, "Please enable 'Ai_macrofy (Macro)' Accessibility Service.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else if (!isAccessibilityServiceEnabled(LayoutAccessibilityService.class)) {
                Toast.makeText(this, "Please enable 'Ai_macrofy (Layout)' Accessibility Service.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else {
                // Check for Gemma model download if it's the selected provider
                if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(appPreferences.getAiProvider()) && !GemmaManager.isModelAvailable(this)) {
                    promptGemmaModelDownload();
                    return; // Stop here, let user decide on download
                }

                if (!MyForegroundService.isMacroRunning) {
                    currentRecognizedText = textViewRecognizedPrompt.getText().toString();
                    if (currentRecognizedText.isEmpty()) {
                        Toast.makeText(this, "Please speak or type a command first.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Log.d("MainActivity", "Start Macro button clicked. Starting service.");
                    // Directly start the service. The service will request permission.
                    startMacro();

                } else {
                    Toast.makeText(this, "Macro is already running.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonStopMacro.setOnClickListener(v -> {
            if (MyForegroundService.isMacroRunning) {
                // The service will handle its own shutdown process.
                stopService(new Intent(this, MyForegroundService.class));
                Log.d("MainActivity", "Stop Macro button clicked. Sent stop intent to service.");
                textViewResult.setText("Macro stop signal sent.");
                Toast.makeText(this, "Stopping macro...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Macro is not running.", Toast.LENGTH_SHORT).show();
            }
        });

        setupDownloadReceiver();
    }

    private void promptGemmaModelDownload() {
        new AlertDialog.Builder(this)
                .setTitle("Gemma Model Download Required")
                .setMessage("The local Gemma model is not yet installed. It needs to be downloaded to be used. This is a one-time download of about 3GB. Download now?")
                .setPositiveButton("Download", (dialog, which) -> startGemmaModelDownload())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startGemmaModelDownload() {
        // **중요**: 이 URL 목록을 GitHub 리포지토리의 실제 Raw 파일 URL로 교체해야 합니다.
        List<String> modelPartUrls = new ArrayList<>(Arrays.asList(
                "https://github.com/maria2580/gemma3_e4b_it_int4/raw/main/gemma-3n-E4B-it-int4.task.part0",
                "https://github.com/maria2580/gemma3_e4b_it_int4/raw/main/gemma-3n-E4B-it-int4.task.part1",
                "https://github.com/maria2580/gemma3_e4b_it_int4/raw/main/gemma-3n-E4B-it-int4.task.part2",
                "https://github.com/maria2580/gemma3_e4b_it_int4/raw/main/gemma-3n-E4B-it-int4.task.part3",
                "https://github.com/maria2580/gemma3_e4b_it_int4/raw/main/gemma-3n-E4B-it-int4.task.part4"
        ));

        Intent intent = new Intent(this, ModelDownloadService.class);
        intent.putStringArrayListExtra("urls", (ArrayList<String>) modelPartUrls);
        intent.putExtra("fileName", GemmaManager.MODEL_FILENAME);
        startService(intent);

        showProgressDialog("Downloading Model...", true);
    }

    private void showProgressDialog(String message, boolean showProgressText) {
        if (progressDialog != null && progressDialog.isShowing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setView(R.layout.dialog_progress);
        progressDialog = builder.create();
        progressDialog.show();

        TextView title = progressDialog.findViewById(R.id.textView_progress_title);
        TextView status = progressDialog.findViewById(R.id.textView_progress_status);
        if (title != null) {
            title.setText(message);
        }
        if (status != null) {
            status.setVisibility(showProgressText ? View.VISIBLE : View.GONE);
        }
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void setupDownloadReceiver() {
        downloadProgressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && ModelDownloadService.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction())) {
                    String statusText = intent.getStringExtra(ModelDownloadService.EXTRA_STATUS_TEXT);
                    boolean isComplete = intent.getBooleanExtra(ModelDownloadService.EXTRA_IS_COMPLETE, false);

                    if (progressDialog != null && progressDialog.isShowing()) {
                        TextView progressTextView = progressDialog.findViewById(R.id.textView_progress_status);
                        if (progressTextView != null && progressTextView.getVisibility() == View.VISIBLE) {
                            progressTextView.setText(statusText);
                        }
                    }

                    if (isComplete) {
                        dismissProgressDialog();
                        boolean isSuccess = intent.getBooleanExtra(ModelDownloadService.EXTRA_IS_SUCCESS, false);
                        Toast.makeText(MainActivity.this, isSuccess ? "Download complete!" : "Download failed.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        };
    }

    private void checkSequentialSetup() {
        if (activeDialog != null && activeDialog.isShowing()) {
            return; // Don't show a new dialog if one is already active
        }
        // Chain of checks. If a check fails, it shows a dialog and returns true.
        if (checkAndShowNotificationDialog()) return;
        if (checkAndShowAccessibilityDialog()) return;
        checkAndShowAssistantDialog(); // This is the last, optional check.
    }

    private boolean checkAndShowNotificationDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                showPermissionDialog(
                        "설정: 알림 권한",
                        "매크로 중단을 위한 버튼을 상태바에 제공하기 위해, 알림권한을 허용해주세요",
                        "허용",
                        () -> ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS_PERMISSION)
                );
                return true; // Dialog shown
            }
        }
        return false; // No dialog needed
    }

    private boolean checkAndShowAccessibilityDialog() {
        if (!isAccessibilityServiceEnabled(LayoutAccessibilityService.class) || !isAccessibilityServiceEnabled(MacroAccessibilityService.class)) {
            showPermissionDialog(
                    "설정: 접근성 서비스",
                    "AI Macrofy는 화면을 보고, 조작하기 위해 접근성 기능을 필요로합니다\n\n다음 화면에서 AI Macrofy를 위한 접근성 서비스를 활성화 해주세요\n\n1. AI Macrofy (Layout)\n2. AI Macrofy (Macro)",
                    "접근성 설정 이동",
                    () -> {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                        Toast.makeText(this, "Find and enable both 'AI Macrofy' services.", Toast.LENGTH_LONG).show();
                    }
            );
            return true; // Dialog shown
        }
        return false; // No dialog needed
    }

    private void checkAndShowAssistantDialog() {
        if (!isThisAppDefaultAssistant()) {
            showPermissionDialog(
                    "설정: 보이스 어시스턴트로 설정하기 (Optional)",
                    "홈버튼이나 전원버튼을 통해 실행되는 assistant 앱을 AI Macrofy로 변경할 수 있습니다.\n\n활성화를 위해 'AI Macrofy'를 기본 어시스턴트 앱으로 설정해주세요.",
                    "어시스턴트 설정 열기",
                    () -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                        try {
                            startActivity(intent);
                            Toast.makeText(this, "Find 'Digital assistant app' and set it to 'AI Macrofy'.", Toast.LENGTH_LONG).show();
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(this, "Could not open default app settings automatically.", Toast.LENGTH_LONG).show();
                        }
                    }
            );
        }
    }

    private boolean isThisAppDefaultAssistant() {
        String assistant = Settings.Secure.getString(getContentResolver(), "assistant");
        if (TextUtils.isEmpty(assistant)) {
            return false;
        }
        ComponentName assistantComponent = ComponentName.unflattenFromString(assistant);
        return assistantComponent != null && assistantComponent.getPackageName().equals(getPackageName());
    }

    private void showPermissionDialog(String title, String message, String positiveButtonText, Runnable onPositiveClick) {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveButtonText, (dialog, which) -> onPositiveClick.run())
                .setNegativeButton("다음에", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }


    @Override
    protected void onResume() {
        super.onResume();
        loadAndDisplayApiKeyStatus();
        updateUiBasedOnServiceState();
        checkSequentialSetup();
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadProgressReceiver, new IntentFilter(ModelDownloadService.ACTION_DOWNLOAD_PROGRESS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadProgressReceiver);
    }

    private void updateUiBasedOnServiceState() {
        if (MyForegroundService.isMacroRunning) {
            textViewResult.setText("Macro is currently running.");
        } else {
            // Check if there's a final message to display or just reset
        }
    }

    private void loadAndDisplayApiKeyStatus() {
        String currentProvider = appPreferences.getAiProvider();
        String apiKey = appPreferences.getApiKeyForCurrentProvider();
        String providerName;
        if (AppPreferences.PROVIDER_GEMINI.equals(currentProvider)) {
            providerName = "Google Gemini";
        } else if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentProvider)) {
            providerName = "Gemma (Local)";
        } else {
            providerName = "OpenAI";
        }

        if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentProvider)) {
            textViewCurrentApiKeyStatus.setText("Current Provider: " + providerName);
        } else if (apiKey != null && !apiKey.isEmpty()) {
            textViewCurrentApiKeyStatus.setText(providerName + " API Key: Loaded");
        } else {
            textViewCurrentApiKeyStatus.setText(providerName + " API Key: Not Set");
        }
    }

    private void startSpeechToText() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO_PERMISSION);
        } else {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command...");
            try {
                startActivityForResult(intent, SPEECH_REQUEST_CODE);
            } catch (ActivityNotFoundException a) {
                Toast.makeText(getApplicationContext(), "Speech recognition not supported on this device.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpeechToText();
            } else {
                Toast.makeText(this, "Audio recording permission denied.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_POST_NOTIFICATIONS_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Notification permission is recommended for stopping the macro.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> result = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (result != null && !result.isEmpty()) {
                currentRecognizedText = result.get(0);
                textViewRecognizedPrompt.setText(currentRecognizedText);
                Log.d("SpeechToText", "Recognized text: " + currentRecognizedText);
            }
        }
    }

    private boolean isAccessibilityServiceEnabled(Class<?> accessibilityServiceClass) {
        String serviceId = getPackageName() + "/" + accessibilityServiceClass.getCanonicalName();
        String enabledServicesSetting = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return enabledServicesSetting != null && enabledServicesSetting.toLowerCase().contains(serviceId.toLowerCase());
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private List<Pair<String, String>> getLaunchableApplications() {
        LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        List<Pair<String, String>> launchableApps = new ArrayList<>();
        if (launcherApps == null) {
            return launchableApps;
        }

        Set<String> addedPackages = new HashSet<>();
        List<UserHandle> profiles = launcherApps.getProfiles();
        for (UserHandle profile : profiles) {
            List<LauncherActivityInfo> apps = launcherApps.getActivityList(null, profile);
            for (LauncherActivityInfo app : apps) {
                String packageName = app.getApplicationInfo().packageName;
                if (!addedPackages.contains(packageName)) {
                    String appName = app.getLabel().toString();
                    launchableApps.add(new Pair<>(packageName, appName));
                    addedPackages.add(packageName);
                }
            }
        }
        Collections.sort(launchableApps, Comparator.comparing(o -> o.second.toLowerCase()));
        return launchableApps;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private String getLaunchableApplicationsListString() {
        // Use the modern LauncherApps service for an accurate list of apps across profiles
        LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps == null) {
            return "Could not access LauncherApps service.\n";
        }

        List<Pair<String, String>> launchableApps = new ArrayList<>();
        Set<String> addedPackages = new HashSet<>(); // To avoid duplicates

        // Get apps from all available user profiles (main, work, secure folder, etc.)
        List<UserHandle> profiles = launcherApps.getProfiles();
        for (UserHandle profile : profiles) {
            List<LauncherActivityInfo> apps = launcherApps.getActivityList(null, profile);
            for (LauncherActivityInfo app : apps) {
                String packageName = app.getApplicationInfo().packageName;
                if (!addedPackages.contains(packageName)) {
                    String appName = app.getLabel().toString();
                    launchableApps.add(new Pair<>(packageName, appName));
                    addedPackages.add(packageName);
                }
            }
        }

        if (launchableApps.isEmpty()) {
            return "No applications found on this device.\n";
        }

        // Sort apps by name
        Collections.sort(launchableApps, Comparator.comparing(o -> o.second.toLowerCase()));

        StringBuilder sb = new StringBuilder();
        sb.append("List of launchable applications (Format: Package Name : Display Name):\n");
        for (Pair<String, String> appEntry : launchableApps) {
            sb.append("- ").append(appEntry.first).append(" : ").append(appEntry.second).append("\n");
        }

        Log.d("MainActivity", "Total launchable apps found (LauncherApps): " + launchableApps.size());
        return sb.toString();
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

        return "No specific application mentioned in the command. Analyze the screen to perform actions.\n";
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private String getSimplifiedLaunchableApplicationsListString() {
        LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
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

    @SuppressLint("SetTextI18n")
    private void startMacro() {
        String recognizedCommand = textViewRecognizedPrompt.getText().toString();
        String currentProvider = appPreferences.getAiProvider();
        String apiKey = appPreferences.getApiKeyForCurrentProvider();

        if (apiKey.isEmpty() && !AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentProvider)) {
            String providerName = AppPreferences.PROVIDER_OPENAI.equals(currentProvider) ? "OpenAI" : "Gemini";
            textViewResult.setText("API Key for " + providerName + " is not set. Please set it in Settings.");
            Toast.makeText(this, "API Key for " + providerName + " is not set. Please set it in Settings.", Toast.LENGTH_LONG).show();
            return;
        }

        String finalSystemPrompt;
        String appsListForPrompt;
        List<Pair<String, String>> allApps = getLaunchableApplications();

        if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentProvider)) {
            appsListForPrompt = getFilteredAppsListForPrompt(recognizedCommand, allApps);
            finalSystemPrompt =
                    "You are an Android automation AI. Your entire response MUST be a single JSON object containing an `actions` array with exactly ONE action object.\n\n" +
                    "## Core Directives\n" +
                    "1.  **CRITICAL: SINGLE ACTION ONLY**: The `actions` array MUST contain only ONE action object. Never output multiple actions in the array.\n" +
                    "2.  **Coordinate System**: The screen is a grid. The top-left corner of the image is `(0,0)` and the bottom-right is `(" + screenWidth + "," + screenHeight + ")`.\n\n" +
                    "## JSON Response Format\n" +
                    "Your response must be ONLY a JSON object with a single key: `actions`. Example: `{\"actions\":[{\"type\":\"touch\",\"coordinates\":{\"x\":123,\"y\":456}}]}`\n\n" +
                    "## Critical Rules\n" +
                    "- After you perform a `touch` or `scroll`, the next turn's response from you MUST be a `wait` action.\n" +
                    "- If a `scroll` action fails to change the screen, you are stuck. Try scrolling `up` or use a `back` gesture in the next turn.\n" +
                    "- When the user's entire request is complete, respond with `{\"actions\":[{\"type\":\"done\"}]}`.\n\n" +
                    "## Action Types (Choose ONE)\n" +
                    "- **Touch**: `{\"type\":\"touch\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}}`\n" +
                    "- **Scroll**: `{\"type\":\"scroll\",\"direction\":\"up|down\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}}`\n" +
                    "- **Open App**: `{\"type\":\"open_application\",\"application_name\":\"PACKAGE_NAME\"}`\n" +
                    "- **Gesture**: `{\"type\":\"gesture\",\"name\":\"back|home\"}`\n" +
                    "- **Wait**: `{\"type\":\"wait\",\"duration\":1500}`\n\n" +
                    "## Available Applications\n" +
                    "Use the package name from this list for the `open_application` action.\n" +
                    appsListForPrompt;
        } else {
            appsListForPrompt = getLaunchableApplicationsListString();
            finalSystemPrompt =
                    "You are an intelligent assistant designed to automate tasks on an Android device. Your primary goal is to analyze the provided screen content (EITHER a screenshot image OR a JSON layout) and the user's intention to generate a sequence of precise actions in a specific JSON format.\n\n" +
                            "## Input Method:\n" +
                            "- **If you receive an image:** The image is a direct screenshot of the device. Analyze it visually to identify UI elements, icons, and text.\n" +
                            "- **If you receive a JSON layout:** This is a structured representation of the screen from the accessibility service. Use this when an image is not available.\n\n" +
                            "## Core Instructions:\n" +
                            // MODIFIED START: Replaced the old Golden Rule with a new, more robust one.
                            "0. **The Golden Rule: Observe, Analyze, Act**: Your entire response MUST be a JSON object with three mandatory keys: `observation`, `analysis`, and `actions`.\n" +
                            "   a. **Step 0: WAIT FOR STABILITY (Crucial!)**: After any action that changes the screen (especially `scroll`), the UI might be unstable. If the previous action was a `scroll` or `touch`, your first instinct MUST be to output a `wait` action for a short duration (e.g., `{\"observation\":[\"Waiting for screen to settle.\"],\"analysis\":\"The previous action may cause UI changes, so I will wait for stability.\",\"actions\":[{\"type\":\"wait\",\"duration\":1500}]}`) before attempting a new observation and analysis. This prevents you from analyzing a partially loaded screen.\n" +
                            "   b. **Step 1: OBSERVE (Fill `observation` field)**: Your first task is to list the text from key visible, clickable UI elements. This is your evidence. List what you actually see. Example: `\"observation\": [\"Login button\", \"Username field\", \"Forgot Password link\"]`.\n" +
                            "   c. **Step 2: ANALYZE (Fill `analysis` field)**: Next, compare your `observation` with the user's goal and the conversation history. Your analysis must state your reasoning. Example: `\"analysis\": \"Based on my observation, the 'Login button' is visible. I will click it to proceed.\"`\n" +
                            "   d. **Step 3: ACT (Fill `actions` field)**: Based strictly on your analysis, generate the single most logical action.\n" +
                            // MODIFIED END
                            "1.  **Analyze Thoroughly**: Carefully examine the entire screen layout JSON, the user's command, conversation history, and any `execution_feedback` to understand the context and identify the most appropriate UI element(s) for the action(s).\n" +
                            "2.  **User Intent is Paramount**: Your primary goal is to fulfill the user's spoken or typed command. Do NOT interact with UI elements related to controlling the macro itself (e.g., 'Start Macro', 'Stop Macro', 'Record Prompt' buttons that might appear in the application's own UI) unless the user *explicitly* asks to control the macro (e.g., \"Stop the macro now\"). Focus on automating tasks within *other* applications or the Android system as per the user's command.\n" +
                            "3.  **Coordinate System**: The screen uses a coordinate system where (0,0) is the top-left corner and (\""+screenWidth+","+screenHeight+") is the bottom-right corner. All coordinates in your output must adhere to this.\n" +
                            // MODIFIED START: Added Stuck Detection logic to the repetition rule.
                            "4.  **Stuck Detection and Repetition**: You will be provided with your previous actions and observations in the conversation history. If you performed a `scroll` action and the new `observation` list is identical or highly similar to the previous one, you are STUCK. If STUCK, you are **FORBIDDEN** from scrolling again in the same direction. Your only recovery options are: a) `scroll` in the opposite direction (`up`), b) try a different interaction (`touch` a relevant button), or c) use a `back` gesture. For non-scroll repetitions, if the screen and action are identical to the last step, use a `back` gesture to avoid loops.\n" +
                            // MODIFIED END
                            "5.  **Flexible Action Output**: Your entire response MUST be a single JSON object containing an \"actions\" key, which holds an array. This array can contain a single action or a sequence of multiple actions to be executed in order. While most situations require only a single action, you can and should send multiple actions in the array for specific scenarios:\n" +
                            "    a. **Virtual Keyboard Input**: This is a primary use case. When a direct `input` action fails or is not feasible, you can simulate typing by sending a sequence of `touch` actions, one for each key on the virtual keyboard. This is ideal for entering PINs, passwords, account numbers, or any text when the standard input method is unreliable.\n" +
                            "    b. **Complex Atomic Sequences**: For operations that require multiple rapid steps where waiting for a screen refresh between each step is inefficient or could break the flow.\n" +
                            "    c. **Default Behavior**: If unsure, sending one action at a time remains the standard and safe method. Both single and multiple action submissions are valid.\n" +
                            "6.  **Task Completion**: If the user's command or the current screen state (and potentially `execution_feedback`) indicates the overall task is completed, output: `{\"observation\":[\"Task finished.\"], \"analysis\":\"The user's command is fully executed.\", \"actions\":[{\"type\":\"done\"}]}`.\n" + // MODIFIED: Task Completion now also follows the new JSON format.
                            "7.  **Use 'wait' Action Wisely**: If you anticipate that an action will trigger a screen transition, new content loading, or an app to start, and the next logical step requires waiting, output a `wait` action with an appropriate `duration`. This can be part of a multi-action sequence (e.g., after a touch, before the next).\n" +
                            "8.  **Action Execution Feedback Processing**: If `execution_feedback` with a 'failure' message is provided, your `analysis` must acknowledge the failure. Do NOT repeat the exact same failed action. Instead, analyze the screen again to find an alternative action. For example, if a `touch` failed, try a different element or `scroll` to find the correct one.\n" +
                            "9.  **Handling Dynamic UI Changes (Dropdowns, Pop-ups, Menus)**:\n" +
                            "    a.  **Interaction with New Context**: If a `touch` action on an element (e.g., 'Create Account' button) reveals a dropdown menu, pop-up, or a new set of choices (e.g., 'Personal', 'Child Account' as shown in the user-provided image example), your NEXT action (or sequence of actions) should generally be to interact with an element *within* that newly appeared menu/context, based on the user's overall goal. Do NOT try to click the original element (e.g., 'Create Account') again if the new menu is now the primary focus for selection.\n" +
                            "    b.  **Re-evaluate Layout**: After any action, especially one that reveals new UI components, carefully re-evaluate the `Current Screen Layout JSON` to understand the new state and available interactive elements.\n" +
                            "    c.  **Default Choices**: If the user's command is general (e.g., just 'Create Account') and a menu of choices appears, and the command doesn't specify which option to pick, try to identify a default or common choice (e.g., 'Personal account'). If no clear default, select the first logical option presented in the new menu. If critically unsure, and other recovery options (like 'back') are not suitable, as a last resort, consider if a `wait` or `done` action is appropriate, or if a specific error feedback has occurred, use that to guide the next step.\n" +
                            "    d.  **Dismissing Unwanted Elements**: If a dropdown, pop-up, or dialog appears that is NOT relevant to the user's current task or command, attempt to dismiss it using a `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` action, or by trying to find a 'close' or 'cancel' button within the new element, or by attempting a touch action outside the bounds of the new element if that's a common way to dismiss it.\n\n" +
                            "    e.  **search, send by keybored interact when needed**: oftenly, there is no search icon or send button on search ui on many application's ui. then touch the input area, if keyboard availabl, you can use enter(change line) button to trigger of send of search."+
                            "## Action Type Restriction:\n" + // This section header is kept for structure.
                            // MODIFIED START: Replaced the old Step-by-Step logic.
                            "## Step-by-Step Logic to Follow:\n" +
                            "Before generating your JSON response, you must follow these internal steps:\n" +
                            "1.  **OBSERVE**: Identify key visible, clickable elements and formulate the `observation` list.\n" +
                            "2.  **COMPARE & ANALYZE**: Compare the current `observation` with the user's goal and the conversation history. Formulate the `analysis` string explaining your reasoning. If you are stuck (observation is unchanged after a scroll), state this in your analysis.\n" +
                            "3.  **ACT**: Based on the analysis, formulate the `actions` array.\n" +
                            "4.  **GENERATE**: Construct the final JSON with all three mandatory keys.\n\n" +
                            // MODIFIED END
                            "**IMPORTANT: You MUST ONLY use the action types explicitly defined below. No other action types or variations are permitted.** Any deviation from the specified JSON structure and action types will result in an error.\n\n" +
                            "## Available Applications:\n" +
                            "When using the `open_application` action, refer to the following list of available applications on the device. Use the PACKAGE_NAME provided.\n" +
                            appsListForPrompt + "\n" +
                            // MODIFIED START: Updated JSON format description.
                            "## JSON Output Format & Action Types:\n" +
                            "Provide your response strictly in the following JSON structure, which MUST include `observation`, `analysis`, and `actions` keys.\n" +
                            "```json\n" +
                            "{\n" +
                            "    \"observation\": \"A list of strings from key visible, clickable elements. Example: [\\\"Profile Picture\\\", \\\"Edit Profile\\\", \\\"Logout\\\"]\",\n" +
                            "    \"analysis\": \"A brief, one-sentence justification linking your observation to your action. MUST be based on evidence from the observation.\",\n" +
                            "    \"actions\": [\n" +
                            "        {\"type\":\"touch\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                            "        {\"type\":\"input\",\"text\":\"STRING_TO_INPUT\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                            "        {\"type\":\"scroll\",\"direction\":\"up|down|left|right\",\"distance\":INTEGER,\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                            "        {\"type\":\"long_touch\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS},\n" +
                            "        {\"type\":\"drag_and_drop\",\"start\":{\"x\":INTEGER,\"y\":INTEGER},\"end\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS},\n" +
                            "        {\"type\":\"double_tap\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                            "        {\"type\":\"swipe\",\"start\":{\"x\":INTEGER,\"y\":INTEGER},\"end\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS},\n" +
                            "        {\"type\":\"gesture\",\"name\":\"back|home|recent_apps\"},\n" +
                            "        {\"type\":\"wait\",\"duration\":MILLISECONDS (e.g., 2500)},\n" +
                            "        {\"type\":\"open_application\",\"application_name\":\"PACKAGE_NAME_STRING\"},\n" +
                            "        {\"type\":\"done\"}\n" +
                            "    ]\n" +
                            "}\n" +
                            "```\n\n" +
                            // MODIFIED END
                            "## Important Notes:\n" +
                            // MODIFIED START: Reworded this note to align with the new rules.
                            "-   **Justification for Scrolling**: You are only permitted to generate a `scroll` action if your `analysis` explicitly states that you have analyzed all elements in your `observation` and none are useful for the current task. A scroll action without this justification is a failure.\n" +
                            // MODIFIED END
                            "-   **Focus on User's Task, Not App Control**: Your main objective is to execute the user's command within other applications or the Android system. Avoid interacting with the UI elements of the macro application itself (like 'Start Macro', 'Settings' buttons shown in the initial screen of this app) unless specifically instructed by the user to control the macro's behavior. If the user's command is, for example, \"Send an email\", your actions should focus on opening the email app, composing, etc., not on clicking buttons within this macro control application.\n" + // 앱 제어 버튼 관련 지침 한 번 더 강조
                            "-   **Package Name for Apps**: When using `open_application`, `application_name` MUST be the package name. **Prioritize using a package name from the 'Available Applications' list if the user's request matches an app in that list.** If the requested app is not in the list, or if the user's request is ambiguous, you may state that the specific app is not found in the provided list or ask for clarification. If you must guess a package name for an unlisted app, clearly indicate that it is a guess.\n" +
                            "-   **Clickable Elements**: Prioritize `\"clickable\": true` elements. If not clickable, consider alternatives.\n" +
                            "-   **`FrameLayout`**: Generally not interactive. Avoid direct touch unless clearly intended.\n" +
                            "-   **Unexpected Screen or Stuck**: If the layout is unexpected or you cannot determine a useful action after receiving failure feedback (especially after a menu interaction), use `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` to try to recover or dismiss unexpected UI elements. Asking for user clarification should be a last resort.\n" +
                            "-   **Precision**: Be precise with coordinates and parameters.\n" +
                            "-   **User's Command is Key**: The user's command drives the goal. `execution_feedback` and UI interaction patterns help you navigate obstacles to reach that goal.";
        }

        Log.d("MainActivity", "startMacro() called with recognized command: " + recognizedCommand);
        textViewResult.setText("Macro starting with command: " + recognizedCommand);

        Intent serviceIntent = new Intent(this, PermissionRequestActivity.class);
        serviceIntent.putExtra("apiKey", apiKey);
        serviceIntent.putExtra("baseSystemPrompt", finalSystemPrompt);
        serviceIntent.putExtra("userCommand", recognizedCommand);
        serviceIntent.putExtra("ai_provider", currentProvider);
        // MediaProjection data is no longer passed from here.

        startActivity(serviceIntent);
    }
}