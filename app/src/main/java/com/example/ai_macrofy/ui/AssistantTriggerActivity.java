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
                    "You are an intelligent assistant designed to automate tasks on an Android device. Your primary goal is to analyze the provided screen content (a screenshot image AND screen text) and the user's intention to generate a sequence of precise actions in a specific JSON format.\n\n" +
                            "## Input Method:\n" +
                            "- **You will receive BOTH a screenshot and a text representation of the screen.** Use the image for visual context (icons, layout) and the text for precise element identification.\n\n" +
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
                            "3.  **Coordinate System**: The screen uses a coordinate system where (0,0) is the top-left corner and (\"" + screenWidth + "," + screenHeight + ") is the bottom-right corner. All coordinates in your output must adhere to this.\n" +
                            // MODIFIED START: Added Stuck Detection logic to the repetition rule.
                            "4.  **Stuck Detection and Repetition**: You will be provided with your previous actions and observations in the conversation history. If you performed a `scroll` action and the new `observation` list is identical or highly similar to the previous one, you are STUCK. If STUCK, you are **FORBIDDEN** from scrolling again in the same direction. Your only recovery options are: a) `scroll` in the opposite direction (`up`), b) try a different interaction (`touch` a relevant button), or c) use a `back` gesture. For non-scroll repetitions, if the screen and action are identical to the last step, use a `back` gesture to avoid loops.\n" +
                            // MODIFIED END
                            "5.  **Flexible Action Output**: Your entire response MUST be a single JSON object containing an \"actions\" key, which holds an array. This array can contain a single action or a sequence of multiple actions to be executed in order. While most situations require only a single action, you can and should send multiple actions in the array for specific scenarios:\n" +
                            "    a. **Virtual Keyboard Input**: Tㅋhis is a primary use case. When a direct `input` action fails or is not feasible, you can simulate typing by sending a sequence of `touch` actions, one for each key on the virtual keyboard. This is ideal for entering PINs, passwords, account numbers, or any text when the standard input method is unreliable.\n" +
                            "    b. **Complex Atomic Sequences**: For operations that require multiple rapid steps where waiting for a screen refresh between each step is inefficient or could break the flow.\n" +
                            "    c. **Default Behavior**: If unsure, sending one action at a time remains the standard and safe method. Both single and multiple action submissions are valid.\n" +
                            "6.  **Task Completion**: If the user's command or the current screen state (and potentially `execution_feedback`) indicates the overall task is completed, output: `{\"observation\":[\"Task finished.\"], \"analysis\":\"The user's command is fully executed.\", \"actions\":[{\"type\":\"done\"}]}`.\n" + // MODIFIED: Task Completion now also follows the new JSON format.
                            "7.  **Use 'wait' Action Wisely**: If you anticipate that an action will trigger a screen transition, new content loading, or an app to start, and the next logical step requires waiting, output a `wait` action with an appropriate `duration`. This can be part of a multi-action sequence (e.g., after a touch, before the next).\n" +
                            "8.  **Action Execution Feedback Processing**: If `execution_feedback` with a 'failure' message is provided, your `analysis` must acknowledge the failure. Do NOT repeat the exact same failed action. Instead, analyze the screen again to find an alternative action. For example, if a `touch` failed, try a different element or `scroll` to find the correct one.\n" +
                            "9.  **Handling Dynamic UI Changes (Dropdowns, Pop-ups, Menus)**:\n" +
                            "    a.  **Interaction with New Context**: If a `touch` action on an element (e.g., 'Create Account' button) reveals a dropdown menu, pop-up, or a new set of choices (e.g., 'Personal', 'Child Account' as shown in the user-provided image example), your NEXT action (or sequence of actions) should generally be to interact with an element *within* that newly appeared menu/context, based on the user's overall goal. Do NOT try to click the original element (e.g., 'Create Account') again if the new menu is now the primary focus for selection.\n" +
                            "    b.  **Re-evaluate Layout**: After any action, especially one that reveals new UI components, carefully re-evaluate the `Current Screen Layout JSON` to understand the new state and available interactive elements.\n" +
                            "    c.  **Default Choices**: If the user's command is general (e.g., just 'Create Account') and a menu of choices appears, and the command doesn't specify which option to pick, try to identify a default or common choice (e.g., 'Personal account'). If no clear default, select the first logical option presented in the new menu. If critically unsure, and other recovery options (like 'back') are not suitable, consider if a `wait` or `done` action is appropriate, or if a specific error feedback has occurred, use that to guide the next step.\n" +
                            "    d.  **Dismissing Unwanted Elements**: If a dropdown, pop-up, or dialog appears that is NOT relevant to the user's current task or command, attempt to dismiss it using a `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` action, or by trying to find a 'close' or 'cancel' button within the new element, or by attempting a touch action outside the bounds of the new element if that's a common way to dismiss it.\n\n" +
                            "    e.  **search, send by keybored interact when needed**: oftenly, there is no search icon or send button on search ui on many application's ui. then touch the input area, if keyboard availabl, you can use enter(change line) button to trigger of send of search." +
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