package com.example.ai_macrofy.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
// import android.content.Context; // AppPreferences에서 사용
import android.content.Context;
import android.content.Intent;
// import android.content.SharedPreferences; // AppPreferences에서 사용
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.ai_macrofy.R; // R import 추가
import com.example.ai_macrofy.services.accessibility.LayoutAccessibilityService; // 수정된 경로
import com.example.ai_macrofy.services.accessibility.MacroAccessibilityService; // 수정된 경로
import com.example.ai_macrofy.services.foreground.MyForegroundService;       // 수정된 경로
import com.example.ai_macrofy.utils.AppPreferences;                       // 수정된 경로
import android.content.pm.ApplicationInfo; // 추가

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final int SPEECH_REQUEST_CODE = 123;

    private TextView textViewResult;
    private EditText textViewRecognizedPrompt;
    private TextView textViewCurrentApiKeyStatus;
    // private Button buttonRecordPrompt; // onCreate에서 초기화
    // private Button buttonSettings; // onCreate에서 초기화

    private AppPreferences appPreferences;
    private boolean isCapturing = false;
    private String currentRecognizedText = "";
    int screenWidth;
    int screenHeight;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main); // 레이아웃 파일 이름 확인
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
        // Insets을 제외한 순수 bounds를 사용하려면 getBounds()를 사용합니다.
        // 시스템 바 (상태 표시줄, 네비게이션 바 등)를 고려하려면 getWindowInsets()와 함께 계산 필요.
        // 여기서는 전체 화면 크기를 가정합니다.
        android.graphics.Rect bounds = windowMetrics.getBounds();
        screenWidth = bounds.width();
        screenHeight = bounds.height();


        appPreferences = new AppPreferences(this);

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
                if (!isCapturing) {
                    currentRecognizedText = textViewRecognizedPrompt.getText().toString();
                    if (currentRecognizedText.isEmpty()) {
                        Toast.makeText(this, "Please speak a command first.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    isCapturing = true;
                    Log.d("MainActivity", "Start Macro button clicked. isCapturing: " + isCapturing);
                    startMacro(currentRecognizedText);
                } else {
                    Toast.makeText(this, "Macro is already running.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonStopMacro.setOnClickListener(v -> {
            if (isCapturing) {
                isCapturing = false;
                Log.d("MainActivity", "Stop Macro button clicked. isCapturing: " + isCapturing);
                stopService(new Intent(this, MyForegroundService.class));
                textViewResult.setText("Macro Stopped");
            } else {
                Toast.makeText(this, "Macro is not running.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAndDisplayApiKeyStatus();
    }

    private void loadAndDisplayApiKeyStatus() {
        String currentProvider = appPreferences.getAiProvider();
        String apiKey = appPreferences.getApiKeyForCurrentProvider();
        String providerName = AppPreferences.PROVIDER_OPENAI.equals(currentProvider) ? "OpenAI" : "Gemini";

        if (apiKey != null && !apiKey.isEmpty()) {
            textViewCurrentApiKeyStatus.setText(providerName + " API Key: Loaded");
            // 각 매니저에 API 키를 여기서 다시 설정할 필요는 없습니다.
            // MyForegroundService가 시작될 때 AppPreferences에서 직접 가져와서 설정합니다.
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
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault()); // 현재 시스템 언어 사용
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

    private String getLaunchableApplicationsListString() {
        PackageManager pm = getPackageManager();
        // 설치된 모든 애플리케이션 정보를 가져옵니다.
        List<ApplicationInfo> allInstalledApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        if (allInstalledApps == null || allInstalledApps.isEmpty()) {
            return "No applications found on this device.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("List of launchable applications (Format: Package Name : Display Name):\n");

        // 애플리케이션 이름을 기준으로 정렬하기 위한 리스트
        List<Pair<String, String>> launchableApps = new ArrayList<>();

        for (ApplicationInfo appInfo : allInstalledApps) {
            // 시스템 앱이 아니고, 업데이트된 시스템 앱도 아닌 경우 (즉, 사용자가 설치한 앱)
            // 또는 시스템 앱이라도 런처 인텐트가 있는 경우 (예: 카메라, 캘린더 등)
            // 실행 가능한 인텐트가 있는지 확인합니다.
            Intent launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName);
            if (launchIntent != null) {
                // 시스템 앱이 아닌 앱만 포함하거나, 혹은 모든 실행 가능한 앱을 포함할 수 있습니다.
                // 사용자의 요청은 "시스템 앱 말고 실행가능한 모든 앱" 이므로,
                // (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 조건으로 사용자가 설치한 앱만 필터링하거나,
                // 이 조건을 제거하여 시스템 앱 중에서도 실행 가능한 것들을 포함할 수 있습니다.
                // 여기서는 일단 모든 실행 가능한 앱 (시스템 앱 포함)을 가져오되,
                // 사용자가 원하면 아래 조건으로 필터링 가능함을 주석으로 남깁니다.

                /*
                // 시스템 앱을 제외하고 싶다면 아래 주석을 해제하세요.
                // (업데이트된 시스템 앱은 사용자가 설치한 앱처럼 취급될 수 있으므로 flags를 주의해서 사용해야 합니다.)
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    // 사용자가 설치했거나 업데이트한 시스템 앱
                } else if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // 순수 시스템 앱 (필요시 제외)
                    // continue; // 이 줄을 활성화하면 순수 시스템 앱은 건너뜁니다.
                }
                */

                String packageName = appInfo.packageName;
                String appName = appInfo.loadLabel(pm).toString();
                launchableApps.add(new Pair<>(packageName, appName));
            }
        }

        // 앱 이름을 기준으로 정렬
        Collections.sort(launchableApps, new Comparator<Pair<String, String>>() {
            @Override
            public int compare(Pair<String, String> o1, Pair<String, String> o2) {
                return o1.second.compareToIgnoreCase(o2.second);
            }
        });

        for (Pair<String, String> appEntry : launchableApps) {
            sb.append("- ").append(appEntry.first).append(" : ").append(appEntry.second).append("\n");
        }

        if (launchableApps.isEmpty()){
            sb.append("No non-system launchable applications found (or filter applied).\n");
        }

        Log.d("MainActivity", "Total launchable apps found and processed: " + launchableApps.size());
        return sb.toString();
    }

    @SuppressLint("SetTextI18n")
    private void startMacro(String recognizedCommand) {
        String currentProvider = appPreferences.getAiProvider();
        String apiKey = appPreferences.getApiKeyForCurrentProvider();

        if (apiKey.isEmpty()) {
            String providerName = AppPreferences.PROVIDER_OPENAI.equals(currentProvider) ? "OpenAI" : "Gemini";
            textViewResult.setText("API Key for " + providerName + " is not set. Please set it in Settings.");
            Toast.makeText(this, "API Key for " + providerName + " is not set. Please set it in Settings.", Toast.LENGTH_LONG).show();
            isCapturing = false;
            return;
        }

        // 실행 가능한 앱 목록 가져오기
        String launchableAppsList = getLaunchableApplicationsListString();
        Log.d("MainActivity", "Launchable apps list: " + launchableAppsList);
        String systemBasePrompt =
                "You are an intelligent assistant designed to automate tasks on an Android device based on screen layouts and user commands. Your primary goal is to analyze the provided screen layout (in JSON format), the user's intention, and any execution feedback to generate a single, precise action in a specific JSON format.\n\n" +
                        "## Core Instructions:\n" +
                        "1.  **Analyze Thoroughly**: Carefully examine the entire screen layout JSON, the user's command, conversation history, and any `execution_feedback` to understand the context and identify the most appropriate UI element for the action.\n" +
                        "2.  **User Intent is Paramount**: Your primary goal is to fulfill the user's spoken or typed command. Do NOT interact with UI elements related to controlling the macro itself (e.g., 'Start Macro', 'Stop Macro', 'Record Prompt' buttons that might appear in the application's own UI) unless the user *explicitly* asks to control the macro (e.g., \"Stop the macro now\"). Focus on automating tasks within *other* applications or the Android system as per the user's command.\n" + // 앱 제어 버튼 관련 지침 추가
                        "3.  **Coordinate System**: The screen uses a coordinate system where (0,0) is the top-left corner and ("+screenWidth+","+screenHeight+") is the bottom-right corner. All coordinates in your output must adhere to this.\n" +
                        "4.  **Prevent Repetition**: If the current screen layout is identical to the previous one, AND the proposed action is the same as the last action performed on that identical screen (check 'Previous Action Context for Repetition Check'), you MUST output `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` to avoid loops, unless the user's command explicitly requests repetition or `execution_feedback` suggests retrying the same action makes sense. Note: If a click reveals a new menu or dropdown, the screen layout has changed, and this rule may not apply directly; instead, focus on interacting with the new menu.\n" +
                        "5.  **Single Action Output**: Your entire response MUST be a single JSON object. This JSON object must contain an \"actions\" key, which holds an array. This array should contain EXACTLY ONE action dictionary specifying the task to perform.\n" +
                        "6.  **Task Completion**: If the user's command or the current screen state (and potentially `execution_feedback`) indicates the overall task is completed, output: `{\"actions\":[{\"type\":\"done\"}]}`.\n" +
                        "7.  **Use 'wait' Action Wisely**: If you anticipate that an action will trigger a screen transition, new content loading, or an app to start, and the next logical step requires waiting, output a `wait` action with an appropriate `duration`.\n" +
                        "8.  **Action Execution Feedback Processing**: If `execution_feedback` (with role 'execution_feedback') is provided in the conversation history, it indicates the result of the previously attempted action. Analyze this feedback and attempt an alternative action. Do NOT simply repeat the failed action unless the feedback suggests a temporary issue.\n" +
                        "9.  **Handling Dynamic UI Changes (Dropdowns, Pop-ups, Menus)**:\n" +
                        "    a.  **Interaction with New Context**: If a `touch` action on an element (e.g., 'Create Account' button) reveals a dropdown menu, pop-up, or a new set of choices (e.g., 'Personal', 'Child Account' as shown in the user-provided image example), your NEXT action should generally be to interact with an element *within* that newly appeared menu/context, based on the user's overall goal. Do NOT try to click the original element (e.g., 'Create Account') again if the new menu is now the primary focus for selection.\n" +
                        "    b.  **Re-evaluate Layout**: After any action, especially one that reveals new UI components, carefully re-evaluate the `Current Screen Layout JSON` to understand the new state and available interactive elements.\n" +
                        "    c.  **Default Choices**: If the user's command is general (e.g., just 'Create Account') and a menu of choices appears, and the command doesn't specify which option to pick, try to identify a default or common choice (e.g., 'Personal account'). If no clear default, select the first logical option presented in the new menu. If critically unsure, and other recovery options (like 'back') are not suitable, as a last resort, consider if a `wait` or `done` action is appropriate, or if a specific error feedback has occurred, use that to guide the next step.\n" +
                        "    d.  **Dismissing Unwanted Elements**: If a dropdown, pop-up, or dialog appears that is NOT relevant to the user's current task or command, attempt to dismiss it using a `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` action, or by trying to find a 'close' or 'cancel' button within the new element, or by attempting a touch action outside the bounds of the new element if that's a common way to dismiss it.\n\n" +
                        "## Action Type Restriction:\n" +
                        "**IMPORTANT: You MUST ONLY use the action types explicitly defined below. No other action types or variations are permitted.** Any deviation from the specified JSON structure and action types will result in an error.\n\n" +
                        "## Available Applications:\n" +
                        "When using the `open_application` action, refer to the following list of available applications on the device. Use the PACKAGE_NAME provided.\n" +
                        launchableAppsList + "\n" +
                        "## JSON Output Format & Action Types:\n" +
                        "Provide your response strictly in the following JSON structure:\n" +
                        "```json\n" +
                        "{\n" +
                        "    \"actions\": [\n" +
                        "        // Exactly ONE of the following action dictionaries:\n" +
                        "        {\"type\":\"touch\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                        "        {\"type\":\"input\",\"text\":\"STRING_TO_INPUT\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                        "        {\"type\":\"scroll\",\"direction\":\"up|down|left|right\",\"distance\":INTEGER},\n" +
                        "        {\"type\":\"long_touch\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS (e.g., 2000)},\n" +
                        "        {\"type\":\"drag_and_drop\",\"start\":{\"x\":INTEGER,\"y\":INTEGER},\"end\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS (e.g., 1500)},\n" +
                        "        {\"type\":\"double_tap\",\"coordinates\":{\"x\":INTEGER,\"y\":INTEGER}},\n" +
                        "        {\"type\":\"swipe\",\"start\":{\"x\":INTEGER,\"y\":INTEGER},\"end\":{\"x\":INTEGER,\"y\":INTEGER},\"duration\":MILLISECONDS (e.g., 800)},\n" +
                        "        {\"type\":\"gesture\",\"name\":\"back|home|recent_apps\"},\n" +
                        "        {\"type\":\"wait\",\"duration\":MILLISECONDS (YOU CHOOSE, e.g., 1000, 2500, 5000) },\n" +
                        "        {\"type\":\"open_application\",\"application_name\":\"PACKAGE_NAME_STRING (e.g., com.google.android.gm, refer to 'Available Applications' list above)\"},\n" +
                        "        {\"type\":\"done\"}\n" +
                        "    ]\n" +
                        "}\n" +
                        "```\n\n" +
                        "## Important Notes:\n" +
                        "-   **Focus on User's Task, Not App Control**: Your main objective is to execute the user's command within other applications or the Android system. Avoid interacting with the UI elements of the macro application itself (like 'Start Macro', 'Settings' buttons shown in the initial screen of this app) unless specifically instructed by the user to control the macro's behavior. If the user's command is, for example, \"Send an email\", your actions should focus on opening the email app, composing, etc., not on clicking buttons within this macro control application.\n" + // 앱 제어 버튼 관련 지침 한 번 더 강조
                        "-   **Package Name for Apps**: When using `open_application`, `application_name` MUST be the package name. **Prioritize using a package name from the 'Available Applications' list if the user's request matches an app in that list.** If the requested app is not in the list, or if the user's request is ambiguous, you may state that the specific app is not found in the provided list or ask for clarification. If you must guess a package name for an unlisted app, clearly indicate that it is a guess.\n" +
                        "-   **Clickable Elements**: Prioritize `\"clickable\": true` elements. If not clickable, consider alternatives.\n" +
                        "-   **`FrameLayout`**: Generally not interactive. Avoid direct touch unless clearly intended.\n" +
                        "-   **Unexpected Screen or Stuck**: If the layout is unexpected or you cannot determine a useful action after receiving failure feedback (especially after a menu interaction), use `{\"actions\":[{\"type\":\"gesture\",\"name\":\"back\"}]}` to try to recover or dismiss unexpected UI elements. Asking for user clarification should be a last resort.\n" +
                        "-   **Precision**: Be precise with coordinates and parameters.\n" +
                        "-   **User's Command is Key**: The user's command drives the goal. `execution_feedback` and UI interaction patterns help you navigate obstacles to reach that goal.";

        Log.d("MainActivity", "startMacro() called with recognized command: " + recognizedCommand);
        textViewResult.setText("Macro starting with command: " + recognizedCommand);

        Intent serviceIntent = new Intent(this, MyForegroundService.class);
        serviceIntent.putExtra("apiKey", apiKey);
        serviceIntent.putExtra("baseSystemPrompt", systemBasePrompt);
        serviceIntent.putExtra("userCommand", recognizedCommand);
        serviceIntent.putExtra("ai_provider", currentProvider);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

}