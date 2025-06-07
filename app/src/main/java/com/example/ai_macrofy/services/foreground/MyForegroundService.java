package com.example.ai_macrofy.services.foreground;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.llm.gpt.GPTManager;
import com.example.ai_macrofy.llm.gemini.GeminiManager;
import com.example.ai_macrofy.services.accessibility.LayoutAccessibilityService;
import com.example.ai_macrofy.services.accessibility.MacroAccessibilityService;
import com.example.ai_macrofy.utils.AppPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyForegroundService extends Service {

    public static volatile MyForegroundService instance;
    private final String CHANNEL_ID = "com.example.ai_macrofy.foreground_channel_v2";
    private final int NOTIFICATION_ID = 1;

    private final Long instructionInterval = 1000L; // LLM 호출 간 기본 간격
    private final Long actionFailureRetryDelay = 2000L; // 액션 실패 시 재시도 전 대기 시간

    private Handler mainHandler;
    private Handler timerHandler;
    private volatile boolean isMacroRunning = false;

    private List<Pair<String, String>> actionHistoryForRepetitionCheck;
    private List<ChatMessage> chatHistory;

    private String currentApiKey;
    private String currentBaseSystemPrompt;
    private String currentUserCommand; // 최초 사용자 명령
    private String currentAiProviderName;
    private AiModelService currentAiModelService;

    private AppPreferences appPreferences;


    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        timerHandler = new Handler(Looper.getMainLooper());
        actionHistoryForRepetitionCheck = new ArrayList<>();
        chatHistory = new ArrayList<>();
        appPreferences = new AppPreferences(this);
        Log.d("MyForegroundService", "onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("MyForegroundService", "onStartCommand received");
        if (intent == null) {
            Log.e("MyForegroundService", "Intent is null in onStartCommand, stopping.");
            stopSelfAppropriately();
            return START_NOT_STICKY;
        }

        currentApiKey = intent.getStringExtra("apiKey");
        currentBaseSystemPrompt = intent.getStringExtra("baseSystemPrompt");
        currentUserCommand = intent.getStringExtra("userCommand"); // 최초 사용자 명령 저장
        currentAiProviderName = intent.getStringExtra("ai_provider");

        if (currentAiProviderName == null || currentAiProviderName.isEmpty()) {
            currentAiProviderName = appPreferences.getAiProvider();
            Log.w("MyForegroundService", "AI Provider missing in intent, loaded from prefs: " + currentAiProviderName);
        }
        if (currentApiKey == null || currentApiKey.isEmpty()) {
            currentApiKey = appPreferences.getApiKeyForCurrentProvider();
            Log.w("MyForegroundService", "API Key missing in intent, loaded from prefs for " + currentAiProviderName);
        }


        if (currentApiKey == null || currentApiKey.isEmpty() ||
                currentBaseSystemPrompt == null || currentBaseSystemPrompt.isEmpty() ||
                currentUserCommand == null || currentUserCommand.isEmpty() || // 최초 명령 확인
                currentAiProviderName == null || currentAiProviderName.isEmpty()) {
            Log.e("MyForegroundService", "Essential data (API Key, Prompt, User Command, Provider) is missing. Stopping service.");
            stopSelfAppropriately();
            return START_NOT_STICKY;
        }

        if (AppPreferences.PROVIDER_GEMINI.equals(currentAiProviderName)) {
            currentAiModelService = new GeminiManager();
        } else {
            currentAiModelService = new GPTManager();
        }
        currentAiModelService.setApiKey(currentApiKey);


        startForegroundNotification();

        if (!isMacroRunning) {
            isMacroRunning = true;
            actionHistoryForRepetitionCheck.clear();
            chatHistory.clear(); // 새 매크로 시작 시 대화 기록 초기화
            Log.d("MyForegroundService", "Starting new macro task sequence for provider: " + currentAiProviderName + " with command: " + currentUserCommand);
            scheduleNextMacroStep(0); // 즉시 첫 단계 실행
        } else {
            Log.d("MyForegroundService", "Macro tasks already running. Settings updated for: " + currentAiProviderName);
            // 실행 중 설정 변경 시 처리 (예: 현재 작업 중단 후 새 설정으로 재시작)
            // 여기서는 일단 기존 작업 계속 진행, 다음번 onStartCommand에서 새 명령으로 시작될 것
        }
        return START_STICKY;
    }

    private void scheduleNextMacroStep(long delayMillis) {
        if (!isMacroRunning) {
            Log.d("MyForegroundService", "Macro is not running, not scheduling next step.");
            return;
        }
        timerHandler.removeCallbacksAndMessages(null); // 이전 예약된 작업 취소
        timerHandler.postDelayed(() -> {
            if (!isMacroRunning) {
                Log.d("MyForegroundService", "Macro stopped during delay, not performing step.");
                return;
            }
            performSingleMacroStep();
        }, delayMillis);
    }

    private void performSingleMacroStep() {
        if (!isMacroRunning || currentAiModelService == null) {
            Log.d("MyForegroundService", "performSingleMacroStep: Macro stopped or AI Service not initialized.");
            if (isMacroRunning) stopSelfAppropriately();
            return;
        }
        Log.d("MyForegroundService", "Performing a single macro step with provider: " + currentAiProviderName);

        if (LayoutAccessibilityService.instance == null) {
            Log.e("MyForegroundService", "LayoutAccessibilityService not available. Stopping macro.");
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Layout Accessibility Service not ready. Macro stopped.", Toast.LENGTH_LONG).show());
            isMacroRunning = false;
            stopSelfAppropriately();
            return;
        }
        final JSONObject layoutData = LayoutAccessibilityService.instance.extractLayoutInfo();
        final String currentScreenLayoutJson = (layoutData != null) ? layoutData.toString() : "No layout data available";
        final String prevActionContextForRepetitionCheck = actionHistoryForRepetitionCheck.toString();

        // LLM에게 전달할 사용자 명령: 최초 명령을 계속 사용하거나, 동적으로 변경 가능
        // 여기서는 최초 명령(currentUserCommand)을 계속 사용하는 것으로 가정
        // 만약 매 스텝마다 사용자 음성 입력을 다시 받는다면 currentUserCommand를 업데이트 해야 함
        String commandForLlm = currentUserCommand; // 또는 매 스텝 변경되는 명령

        currentAiModelService.generateResponse(
                currentBaseSystemPrompt,
                new ArrayList<>(chatHistory),
                currentScreenLayoutJson,
                commandForLlm,
                prevActionContextForRepetitionCheck,
                new ModelResponseCallback() {
                    @Override
                    public void onSuccess(String rawResponse) {
                        if (!isMacroRunning) return;

                        Log.d("MyForegroundService", currentAiProviderName + " Raw Response: " + rawResponse);

                        String pureJsonAction = null;

                        // 1. 마크다운 코드 블록 (```json ... ``` 또는 ``` ... ```) 제거 시도
                        String cleanedResponse = rawResponse.trim();
                        if (cleanedResponse.startsWith("```json")) {
                            cleanedResponse = cleanedResponse.substring(7); // "```json" 제거
                            if (cleanedResponse.endsWith("```")) {
                                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3); // 끝 "```" 제거
                            }
                        } else if (cleanedResponse.startsWith("```")) {
                            cleanedResponse = cleanedResponse.substring(3); // "```" 제거
                            if (cleanedResponse.endsWith("```")) {
                                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
                            }
                        }
                        cleanedResponse = cleanedResponse.trim(); // 앞뒤 공백 제거

                        // 2. 중괄호로 시작하고 끝나는 JSON 객체 찾기 (기존 로직)
                        //    cleanedResponse에서 JSON을 찾도록 수정
                        String jsonObjectRegexString = "\\{(?:[^{}]|\\{(?:[^{}]|\\{[^{}]*\\})*\\})*\\}";
                        Pattern pattern = Pattern.compile(jsonObjectRegexString);
                        Matcher matcher = pattern.matcher(cleanedResponse); // rawResponse 대신 cleanedResponse 사용

                        if (matcher.find()) {
                            pureJsonAction = matcher.group(0);
                            Log.d("MyForegroundService", "Extracted JSON: " + pureJsonAction);
                        }


                        if (pureJsonAction == null) {
                            Log.e("MyForegroundService", "Could not extract JSON from " + currentAiProviderName + " response after cleaning. Cleaned: " + cleanedResponse.substring(0, Math.min(cleanedResponse.length(), 200)));
                            addExecutionFeedbackToHistory("LLM response parsing failed: Could not extract JSON. Cleaned response (first 200 chars): " + cleanedResponse.substring(0, Math.min(cleanedResponse.length(),200)));
                            scheduleNextMacroStep(actionFailureRetryDelay);
                            return;
                        }

                        // ... (userQueryForThisTurn 생성 및 기록 로직은 이전과 유사하게 이어짐)
                        // userQueryForThisTurn 생성은 commandForLlm, currentScreenLayoutJson, prevActionContextForRepetitionCheck를 사용해야 합니다.
                        String userQueryForThisTurn = "User Command: " + commandForLlm + "\n" + // commandForLlm 사용
                                "Current Screen Layout JSON:\n" + currentScreenLayoutJson + "\n" +
                                "Previous Action Context for Repetition Check:\n" + prevActionContextForRepetitionCheck;


                        // MacroAccessibilityService로 실행 요청 전, LLM 응답(pureJsonAction) 기록
                        // 이전에 이 부분이 reportActionCompleted로 옮겨졌거나 명확하지 않았다면 여기서 기록
                        chatHistory.add(new ChatMessage("user", userQueryForThisTurn));
                        chatHistory.add(new ChatMessage("assistant", pureJsonAction)); // LLM이 생성한 JSON 액션
                        limitChatHistory(); // chatHistory 크기 제한


                        // LLM 응답 (액션 JSON)을 MacroAccessibilityService로 전달하여 실행
                        if (MacroAccessibilityService.instance != null) {
                            MacroAccessibilityService.instance.executeActionsFromJson(pureJsonAction);
                        } else {
                            Log.e("MyForegroundService", "MacroAccessibilityService instance is null. Cannot execute actions.");
                            addExecutionFeedbackToHistory("Internal Error: MacroAccessibilityService not available.");
                            scheduleNextMacroStep(actionFailureRetryDelay); // Macro 서비스 없으면 재시도
                        }
                    }

                    @Override
                    public void onError(String error) {
                        if (!isMacroRunning) return;
                        Log.e("MyForegroundService", currentAiProviderName + " API Error: " + error);
                        mainHandler.post(() -> Toast.makeText(getApplicationContext(), currentAiProviderName + " API Error: " + error + ". Retrying.", Toast.LENGTH_LONG).show());
                        addExecutionFeedbackToHistory("LLM API Error: " + error);
                        scheduleNextMacroStep(actionFailureRetryDelay);
                    }
                });
    }

    /**
     * MacroAccessibilityService로부터 액션 실행 결과를 보고받는 메소드.
     * @param success 성공 여부
     * @param feedback 실패 시 이유, 성공 시 null 가능
     */
    public void reportActionCompleted(boolean success, @Nullable String feedback) {
        if (!isMacroRunning) return;

        if (success) {
            Log.d("MyForegroundService", "Action reported as successful.");
            // 성공 시 다음 LLM 호출 예약 (done 액션이 아닐 경우)
            // 'done' 액션은 MacroAccessibilityService에서 MyForegroundService.instance.stopMacroExecution()을 호출하여 처리
            // 또는, LLM이 "done"을 반환했는지 여기서 확인하여 중지
            // (onSuccess에서 pureJsonAction을 파싱하여 "done"인지 확인하는 로직 추가 가능)
            // 현재는 MacroAccessibilityService에서 'done' 시 바로 return 하므로,
            // 이 콜백이 'done'에 대해 호출되지 않거나, 호출되더라도 다음 스텝을 예약하면 안됨.
            // 'done' 액션이 성공적으로 MacroAccessibilityService에 의해 처리되었다면,
            // MyForegroundService의 stopMacroExecution()이 호출되어 isMacroRunning = false가 되어야 함.
            // 따라서 아래 scheduleNextMacroStep은 isMacroRunning이 true일 때만 실행됨.
            scheduleNextMacroStep(instructionInterval);
        } else {
            Log.e("MyForegroundService", "Action reported as failed: " + feedback);
            if (getApplicationContext() != null) { // Context null check 추가
                mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Action Failed: " + feedback + ". LLM will be informed.", Toast.LENGTH_LONG).show());
            }
            addExecutionFeedbackToHistory(feedback != null ? feedback : "Unknown action execution error.");
            scheduleNextMacroStep(actionFailureRetryDelay); // 실패 시 잠시 후 LLM 재호출
        }
        // limitChatHistory(); // addExecutionFeedbackToHistory 내부에서 호출되므로 중복 호출 방지
    }

    private void addExecutionFeedbackToHistory(String feedbackText) {
        chatHistory.add(new ChatMessage("execution_feedback", feedbackText));
        limitChatHistory();
    }

    private void limitChatHistory() {
        while (chatHistory.size() > 20) { // 최대 10쌍 (user/assistant 또는 user/feedback)
            chatHistory.remove(0);
        }
    }

    public void stopMacroExecution() {
        Log.d("MyForegroundService", "stopMacroExecution called externally (e.g., by 'done' action).");
        isMacroRunning = false; // 매크로 중지 플래그 설정
        stopSelfAppropriately();
    }


    private void stopSelfAppropriately() {
        Log.d("MyForegroundService", "stopSelfAppropriately called. isMacroRunning: " + isMacroRunning);
        // isMacroRunning 플래그를 확인하여 실제 중지 여부 결정 가능
        timerHandler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
        instance = null; // 명시적으로 null 처리
        Log.d("MyForegroundService", "Service instance stopped and nulled.");
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Macrofy Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("AI Macrofy is running background tasks.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        String providerText = currentAiProviderName != null ? currentAiProviderName : "AI";
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Macrofy Active")
                .setContentText("Macro running with " + providerText + " provider.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build();

        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.d("MyForegroundService", "Service started in foreground with provider: " + providerText);
        } catch (Exception e) {
            Log.e("MyForegroundService", "Error starting foreground service: " + e.getMessage(), e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d("MyForegroundService", "onDestroy called for MyForegroundService.");
        isMacroRunning = false;
        if (timerHandler != null) {
            timerHandler.removeCallbacksAndMessages(null);
        }
        stopForeground(true); // 확실하게 포그라운드 서비스 중지
        instance = null;
        super.onDestroy();
        Log.d("MyForegroundService", "MyForegroundService fully destroyed.");
    }
}