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
import android.net.Uri;
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
import com.example.ai_macrofy.utils.PromptManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        // --- 추가: 다른 앱 위에 표시 권한 확인 ---
        if (checkAndShowOverlayPermissionDialog()) return;
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

    private boolean checkAndShowOverlayPermissionDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showPermissionDialog(
                    "설정: 다른 앱 위에 표시 권한",
                    "백그라운드 웹 작업의 안정성을 위해 '다른 앱 위에 표시' 권한이 필요합니다. 이 권한은 보이지 않는 1픽셀 창을 띄워 웹뷰가 항상 활성화되도록 유지하는 데 사용됩니다.",
                    "권한 설정으로 이동",
                    () -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                        Toast.makeText(this, "AI Macrofy를 찾아 권한을 허용해주세요.", Toast.LENGTH_LONG).show();
                    }
            );
            return true; // Dialog shown
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

        Log.d("MainActivity", "Total launchable apps found (from cache): " + launchableApps.size());
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

        if (apiKey.isEmpty() && !AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentProvider) && !AppPreferences.PROVIDER_GEMINI_WEB.equals(currentProvider)) {
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
        } else {
            appsListForPrompt = getLaunchableApplicationsListString();
        }
        finalSystemPrompt = PromptManager.getSystemPrompt(currentProvider, screenWidth, screenHeight, appsListForPrompt);

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