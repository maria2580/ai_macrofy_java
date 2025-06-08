package com.example.ai_macrofy.ui;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AssistantTriggerActivity extends AppCompatActivity {

    private static final String TAG = "AssistantTriggerActivity";
    public static final String ACTION_EXECUTE_MACRO = "com.example.ai_macrofy.action.EXECUTE_MACRO";
    public static final String EXTRA_USER_COMMAND = "com.example.ai_macrofy.extra.USER_COMMAND";
    private static final int SPEECH_REQUEST_CODE = 124; // Different from MainActivity's code

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
        if (intent != null) {
            if (ACTION_EXECUTE_MACRO.equals(intent.getAction())) {
                String userCommand = intent.getStringExtra(EXTRA_USER_COMMAND);
                if (userCommand != null && !userCommand.isEmpty()) {
                    Log.d(TAG, "Received command from existing macro action: " + userCommand);
                    startMacroWithCommand(userCommand);
                } else {
                    Log.e(TAG, "No user command provided in the intent.");
                    Toast.makeText(this, "Error: No command received.", Toast.LENGTH_SHORT).show();
                    finish();
                }
            } else if (Intent.ACTION_ASSIST.equals(intent.getAction())) {
                Log.d(TAG, "Triggered by Assistant action. Starting voice recognition.");
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
                startMacroWithCommand(recognizedText);
            } else {
                Toast.makeText(this, "Could not recognize speech.", Toast.LENGTH_SHORT).show();
            }
        } else {
            // If speech recognition was cancelled or failed, just finish the activity.
            Log.d(TAG, "Speech recognition cancelled or failed.");
        }
        finish();
    }

    private void startMacroWithCommand(String command) {
        new Thread(() -> {
            String launchableAppsList = getLaunchableApplicationsListString();
            runOnUiThread(() -> startMacro(command, launchableAppsList));
        }).start();
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
                "You are an intelligent assistant designed to automate tasks on an Android device based on screen layouts and user commands. Your fundamental role is to break down complex, multi-step procedures into a series of simple, executable actions. Tasks like creating a new account, logging in, or navigating intricate menus are well within your capabilities because you can process them step-by-step, using conversation history and screen context to guide you. Therefore, do not prematurely conclude that a task is too complex. Your primary goal is to analyze the provided screen layout (in JSON format), the user's intention, and any execution feedback to generate a sequence of one or more precise actions in a specific JSON format.\n\n" +
                        "## Core Instructions:\n" +
                        // MODIFIED START: Replaced the old Golden Rule with a new, more robust one.
                        "0. **The Golden Rule: Observe, Analyze, Act**: Your entire response MUST be a JSON object with three mandatory keys: `observation`, `analysis`, and `actions`.\n" +
                        "   a. **Step 0: WAIT FOR STABILITY (Crucial!)**: After any action that changes the screen (especially `scroll`), the UI might be unstable. If the previous action was a `scroll` or `touch`, your first instinct MUST be to output a `wait` action for a short duration (e.g., `{\"observation\":[\"Waiting for screen to settle.\"],\"analysis\":\"The previous action may cause UI changes, so I will wait for stability.\",\"actions\":[{\"type\":\"wait\",\"duration\":1500}]}`) before attempting a new observation and analysis. This prevents you from analyzing a partially loaded screen.\n" +
                        "   b. **Step 1: OBSERVE (Fill `observation` field)**: Your first task is to list the text from key visible, clickable UI elements. This is your evidence. List what you actually see. Example: `\"observation\": [\"Login button\", \"Username field\", \"Forgot Password link\"]`.\n" +
                        "   c. **Step 2: ANALYZE (Fill `analysis` field)**: Next, compare your `observation` with the user's goal and any `Previous Step` info provided in the history. Your analysis must state your reasoning. Example: `\"analysis\": \"Based on my observation, the 'Login button' is visible. I will click it to proceed.\"`\n" +
                        "   d. **Step 3: ACT (Fill `actions` field)**: Based strictly on your analysis, generate the single most logical action.\n" +
                        // MODIFIED END
                        "1.  **Analyze Thoroughly**: Carefully examine the entire screen layout JSON, the user's command, conversation history, and any `execution_feedback` to understand the context and identify the most appropriate UI element(s) for the action(s).\n" +
                        "2.  **User Intent is Paramount**: Your primary goal is to fulfill the user's spoken or typed command. Do NOT interact with UI elements related to controlling the macro itself (e.g., 'Start Macro', 'Stop Macro', 'Record Prompt' buttons that might appear in the application's own UI) unless the user *explicitly* asks to control the macro (e.g., \"Stop the macro now\"). Focus on automating tasks within *other* applications or the Android system as per the user's command.\n" +
                        "3.  **Coordinate System**: The screen uses a coordinate system where (0,0) is the top-left corner and (\""+screenWidth+","+screenHeight+") is the bottom-right corner. All coordinates in your output must adhere to this.\n" +
                        // MODIFIED START: Added Stuck Detection logic to the repetition rule.
                        "4.  **Stuck Detection and Repetition**: You will be provided with your previous action and observation in the history. If you performed a `scroll` action and the new `observation` list is identical or highly similar to the previous one, you are STUCK. If STUCK, you are **FORBIDDEN** from scrolling again in the same direction. Your only recovery options are: a) `scroll` in the opposite direction (`up`), b) try a different interaction (`touch` a relevant button), or c) use a `back` gesture. For non-scroll repetitions, if the screen and action are identical to the last step, use a `back` gesture to avoid loops.\n" +
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
                        "    c.  **Default Choices**: If the user's command is general (e.g., just 'Create Account') and a menu of choices appears, and the command doesn't specify which option to pick, try to identify a default or common choice (e.g., 'Personal account'). If no clear default, select the first logical option presented in the new menu. If critically unsure, and other recovery options (like 'back') are not suitable, as a last resort, consider if a `wait` or `done` action is appropriate, or if a specific error feedback has occurred, use that to guide the next step.\n" +
                        "    d.  **Dismissing Unwanted Elements**: If a dropdown, pop-up, or dialog appears that is NOT relevant to the user's current task or command, attempt to dismiss it using a `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` action, or by trying to find a 'close' or 'cancel' button within the new element, or by attempting a touch action outside the bounds of the new element if that's a common way to dismiss it.\n\n" +
                        "    e.  **search, send by keybored interact when needed**: oftenly, there is no search icon or send button on search ui on many application's ui. then touch the input area, if keyboard availabl, you can use enter(change line) button to trigger of send of search."+
                        "## Action Type Restriction:\n" + // This section header is kept for structure.
                        // MODIFIED START: Replaced the old Step-by-Step logic.
                        "## Step-by-Step Logic to Follow:\n" +
                        "Before generating your JSON response, you must follow these internal steps:\n" +
                        "1.  **OBSERVE**: Identify key visible, clickable elements and formulate the `observation` list.\n" +
                        "2.  **COMPARE & ANALYZE**: Compare the current `observation` with the user's goal and any `Previous Step` data. Formulate the `analysis` string explaining your reasoning. If you are stuck (observation is unchanged after a scroll), state this in your analysis.\n" +
                        "3.  **ACT**: Based on the analysis, formulate the `actions` array.\n" +
                        "4.  **GENERATE**: Construct the final JSON with all three mandatory keys.\n\n" +
                        // MODIFIED END
                        "**IMPORTANT: You MUST ONLY use the action types explicitly defined below. No other action types or variations are permitted.** Any deviation from the specified JSON structure and action types will result in an error.\n\n" +
                        "## Available Applications:\n" +
                        "When using the `open_application` action, refer to the following list of available applications on the device. Use the PACKAGE_NAME provided.\n" +
                        launchableAppsList + "\n" +
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

        // The activity is finished in onActivityResult or after starting the macro for the EXECUTE_MACRO action
    }
}