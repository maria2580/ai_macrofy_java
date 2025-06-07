package com.example.ai_macrofy.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.ai_macrofy.services.foreground.MyForegroundService;
import com.example.ai_macrofy.utils.AppPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AssistantTriggerActivity extends AppCompatActivity {

    private static final String TAG = "AssistantTriggerActivity";
    public static final String ACTION_EXECUTE_MACRO = "com.example.ai_macrofy.action.EXECUTE_MACRO";
    public static final String EXTRA_USER_COMMAND = "com.example.ai_macrofy.extra.USER_COMMAND";

    private AppPreferences appPreferences;
    private int screenWidth;
    private int screenHeight;

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
        if (intent != null && ACTION_EXECUTE_MACRO.equals(intent.getAction())) {
            String userCommand = intent.getStringExtra(EXTRA_USER_COMMAND);
            if (userCommand != null && !userCommand.isEmpty()) {
                Log.d(TAG, "Received command from Assistant: " + userCommand);
                // Run heavy work in background to avoid blocking UI thread
                new Thread(() -> {
                    String launchableAppsList = getLaunchableApplicationsListString();
                    runOnUiThread(() -> startMacro(userCommand, launchableAppsList));
                }).start();
            } else {
                Log.e(TAG, "No user command provided in the intent.");
                Toast.makeText(this, "Error: No command received.", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else {
            Log.e(TAG, "Activity started with incorrect action or null intent.");
            Toast.makeText(this, "Error: Invalid trigger.", Toast.LENGTH_SHORT).show();
            finish();
        }
        // Finish is now called after the background task is started or on error
    }

    private String getLaunchableApplicationsListString() {
        PackageManager pm = getPackageManager();
        // Get all installed applications
        List<ApplicationInfo> allInstalledApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        if (allInstalledApps == null || allInstalledApps.isEmpty()) {
            return "No applications found on this device.\n";
        }

        List<Pair<String, String>> launchableApps = new ArrayList<>();
        for (ApplicationInfo appInfo : allInstalledApps) {
            // Check if the application has a launch intent
            if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                String packageName = appInfo.packageName;
                String appName = appInfo.loadLabel(pm).toString();
                launchableApps.add(new Pair<>(packageName, appName));
            }
        }

        if (launchableApps.isEmpty()) {
            return "No launchable applications found.\n";
        }

        // Sort apps by name
        Collections.sort(launchableApps, Comparator.comparing(o -> o.second.toLowerCase()));

        StringBuilder sb = new StringBuilder();
        sb.append("List of launchable applications (Format: Package Name : Display Name):\n");
        for (Pair<String, String> appEntry : launchableApps) {
            sb.append("- ").append(appEntry.first).append(" : ").append(appEntry.second).append("\n");
        }
        return sb.toString();
    }


    private void startMacro(String recognizedCommand, String launchableAppsList) {
        String currentProvider = appPreferences.getAiProvider();
        String apiKey = appPreferences.getApiKeyForCurrentProvider();

        if (apiKey.isEmpty()) {
            String providerName = AppPreferences.PROVIDER_OPENAI.equals(currentProvider) ? "OpenAI" : "Gemini";
            Log.e(TAG, "API Key for " + providerName + " is not set.");
            Toast.makeText(this, "API Key for " + providerName + " is not set. Please set it in the app.", Toast.LENGTH_LONG).show();
            finish(); // Finish activity if API key is missing
            return;
        }

        String systemBasePrompt =
                "You are an intelligent assistant designed to automate tasks on an Android device based on screen layouts and user commands. Your primary goal is to analyze the provided screen layout (in JSON format), the user's intention, and any execution feedback to generate a single, precise action in a specific JSON format.\n\n" +
                        "## Core Instructions:\n" +
                        "1.  **Analyze Thoroughly**: Carefully examine the entire screen layout JSON, the user's command, conversation history, and any `execution_feedback` to understand the context and identify the most appropriate UI element for the action.\n" +
                        "2.  **User Intent is Paramount**: Your primary goal is to fulfill the user's spoken or typed command. Do NOT interact with UI elements related to controlling the macro itself (e.g., 'Start Macro', 'Stop Macro', 'Record Prompt' buttons that might appear in the application's own UI) unless the user *explicitly* asks to control the macro (e.g., \"Stop the macro now\"). Focus on automating tasks within *other* applications or the Android system as per the user's command.\n" +
                        "3.  **Coordinate System**: The screen uses a coordinate system where (0,0) is the top-left corner and (" + screenWidth + "," + screenHeight + ") is the bottom-right corner. All coordinates in your output must adhere to this.\n" +
                        "4.  **Prevent Repetition**: If the current screen layout is identical to the previous one, AND the proposed action is the same as the last action performed on that identical screen (check 'Previous Action Context for Repetition Check'), you MUST output `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` to avoid loops.\n" +
                        "5.  **Single Action Output**: Your entire response MUST be a single JSON object with an \"actions\" key holding an array of EXACTLY ONE action dictionary.\n" +
                        "6.  **Task Completion**: If the task is completed, output: `{\"actions\":[{\"type\":\"done\"}]}`.\n" +
                        "7.  **Use 'wait' Action Wisely**: If you anticipate a screen transition or loading, use a `wait` action.\n" +
                        "8.  **Action Execution Feedback Processing**: Analyze `execution_feedback` to attempt an alternative action if the previous one failed.\n\n" +
                        "## Action Type Restriction:\n" +
                        "**IMPORTANT: You MUST ONLY use the action types explicitly defined below.**\n\n" +
                        "## Available Applications:\n" +
                        "When using the `open_application` action, refer to the following list of available applications on the device. Use the PACKAGE_NAME provided.\n" +
                        launchableAppsList + "\n" +
                        "## JSON Output Format & Action Types:\n" +
                        "```json\n" +
                        "{\n" +
                        "    \"actions\": [\n" +
                        "        {\"type\":\"touch\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                        "        {\"type\":\"input\",\"text\":\"STRING_TO_INPUT\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                        "        {\"type\":\"scroll\",\"direction\":\"up|down|left|right\",\"distance\":INTEGER},\n" +
                        "        {\"type\":\"long_touch\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS},\n" +
                        "        {\"type\":\"drag_and_drop\",\"start\":{\"x\":INTEGER,\"y\":INTEGER},\"end\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS},\n" +
                        "        {\"type\":\"double_tap\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                        "        {\"type\":\"swipe\",\"start\":{\"x\":INTEGER,\"y\":INTEGER},\"end\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS},\n" +
                        "        {\"type\":\"gesture\",\"name\":\"back|home|recent_apps\"},\n" +
                        "        {\"type\":\"wait\",\"duration\":MILLISECONDS},\n" +
                        "        {\"type\":\"open_application\",\"application_name\":\"PACKAGE_NAME_STRING\"},\n" +
                        "        {\"type\":\"done\"}\n" +
                        "    ]\n" +
                        "}\n" +
                        "```";

        Log.d(TAG, "Starting MyForegroundService with command: " + recognizedCommand);
        Toast.makeText(this, "Starting Ai_macrofy command...", Toast.LENGTH_SHORT).show();

        Intent serviceIntent = new Intent(this, MyForegroundService.class);
        serviceIntent.putExtra("apiKey", apiKey);
        serviceIntent.putExtra("baseSystemPrompt", systemBasePrompt);
        serviceIntent.putExtra("userCommand", recognizedCommand);
        serviceIntent.putExtra("ai_provider", currentProvider);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Finish the invisible activity after starting the service
        finish();
    }
}