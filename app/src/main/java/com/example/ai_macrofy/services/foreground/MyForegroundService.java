package com.example.ai_macrofy.services.foreground;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
    private static final String ACTION_STOP_MACRO = "com.example.ai_macrofy.ACTION_STOP_MACRO";


    private final Long instructionInterval = 100L; // LLM 호출 간 기본 간격
    private final Long actionFailureRetryDelay = 200L; // 액션 실패 시 재시도 전 대기 시간
    private static final int MAX_SERVICE_CHECK_ATTEMPTS= 10; // 10 * 500ms = 5 seconds
    private static final long SERVICE_CHECK_INTERVAL_MS = 500;
    private static final long CHAT_HISTORY_SIZE_LIMIT=100;

    private Handler mainHandler;
    private Handler timerHandler;
    public static boolean isMacroRunning = false;

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

        if (intent != null && ACTION_STOP_MACRO.equals(intent.getAction())) {
            Log.d("MyForegroundService", "Stop action received from notification.");
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Macro stopped by user.", Toast.LENGTH_SHORT).show());
            stopMacroExecution();
            return START_NOT_STICKY;
        }

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
            checkServicesAndStartMacro(0);
        } else {
            Log.d("MyForegroundService", "Macro tasks already running. Settings updated for: " + currentAiProviderName);
        }
        return START_STICKY;
    }

    private void checkServicesAndStartMacro(int attempt) {
        if (LayoutAccessibilityService.instance == null || MacroAccessibilityService.instance == null) {
            if (attempt < MAX_SERVICE_CHECK_ATTEMPTS) {
                Log.w("MyForegroundService", "Accessibility services not ready yet. Attempt " + (attempt + 1) + "/" + MAX_SERVICE_CHECK_ATTEMPTS + ". Retrying in " + SERVICE_CHECK_INTERVAL_MS + "ms.");
                timerHandler.postDelayed(() -> checkServicesAndStartMacro(attempt + 1), SERVICE_CHECK_INTERVAL_MS);
            } else {
                Log.e("MyForegroundService", "Accessibility services not available after " + (MAX_SERVICE_CHECK_ATTEMPTS * SERVICE_CHECK_INTERVAL_MS) + "ms. Stopping macro.");
                mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Accessibility Services not ready. Macro stopped.", Toast.LENGTH_LONG).show());
                isMacroRunning = false;
                stopSelfAppropriately();
            }
        } else {
            Log.d("MyForegroundService", "Accessibility services are ready. Starting macro steps.");
            scheduleNextMacroStep(0); // 즉시 첫 단계 실행
        }
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

        // This check is now mostly a safeguard, as checkServicesAndStartMacro should prevent this state.
        if (LayoutAccessibilityService.instance == null || MacroAccessibilityService.instance == null) {
            Log.e("MyForegroundService", "AccessibilityService became unavailable mid-execution. Stopping macro.");
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Accessibility Service disconnected unexpectedly. Macro stopped.", Toast.LENGTH_LONG).show());
            isMacroRunning = false;
            stopSelfAppropriately();
            return;
        }
        final JSONObject layoutData = LayoutAccessibilityService.instance.extractLayoutInfo();
        final String currentScreenLayoutJson = (layoutData != null) ? layoutData.toString() : "No layout data available";
        final String prevActionContextForRepetitionCheck = actionHistoryForRepetitionCheck.toString();

        String commandForLlm = currentUserCommand;

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

                        String cleanedResponse = rawResponse.trim();
                        if (cleanedResponse.startsWith("```json")) {
                            cleanedResponse = cleanedResponse.substring(7);
                            if (cleanedResponse.endsWith("```")) {
                                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
                            }
                        } else if (cleanedResponse.startsWith("```")) {
                            cleanedResponse = cleanedResponse.substring(3);
                            if (cleanedResponse.endsWith("```")) {
                                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.length() - 3);
                            }
                        }
                        cleanedResponse = cleanedResponse.trim();

                        String jsonObjectRegexString = "\\{(?:[^{}]|\\{(?:[^{}]|\\{[^{}]*\\})*\\})*\\}";
                        Pattern pattern = Pattern.compile(jsonObjectRegexString);
                        Matcher matcher = pattern.matcher(cleanedResponse);

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

                        String userQueryForThisTurn = "User Command: " + commandForLlm + "\n" +
                                "Current Screen Layout JSON:\n" + currentScreenLayoutJson + "\n" +
                                "Previous Action Context for Repetition Check:\n" + prevActionContextForRepetitionCheck;


                        chatHistory.add(new ChatMessage("user", userQueryForThisTurn));
                        chatHistory.add(new ChatMessage("assistant", pureJsonAction));
                        limitChatHistory();


                        if (MacroAccessibilityService.instance != null) {
                            MacroAccessibilityService.instance.executeActionsFromJson(pureJsonAction);
                        } else {
                            Log.e("MyForegroundService", "MacroAccessibilityService instance is null. Cannot execute actions.");
                            addExecutionFeedbackToHistory("Internal Error: MacroAccessibilityService not available.");
                            scheduleNextMacroStep(actionFailureRetryDelay);
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

    public void reportActionCompleted(boolean success, @Nullable String feedback) {
        if (!isMacroRunning) return;

        if (success) {
            Log.d("MyForegroundService", "Action reported as successful.");
            scheduleNextMacroStep(instructionInterval);
        } else {
            Log.e("MyForegroundService", "Action reported as failed: " + feedback);
            if (getApplicationContext() != null) {
                mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Action Failed: " + feedback + ". LLM will be informed.", Toast.LENGTH_LONG).show());
            }
            addExecutionFeedbackToHistory(feedback != null ? feedback : "Unknown action execution error.");
            scheduleNextMacroStep(actionFailureRetryDelay);
        }
    }

    private void addExecutionFeedbackToHistory(String feedbackText) {
        chatHistory.add(new ChatMessage("execution_feedback", feedbackText));
        limitChatHistory();
    }

    private void limitChatHistory() {
        while (chatHistory.size() > CHAT_HISTORY_SIZE_LIMIT) {
            chatHistory.remove(0);
        }
    }

    public void stopMacroExecution() {
        Log.d("MyForegroundService", "stopMacroExecution called. Stopping service.");
        isMacroRunning = false;
        stopSelfAppropriately();
    }


    private void stopSelfAppropriately() {
        Log.d("MyForegroundService", "stopSelfAppropriately called.");
        timerHandler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();
        instance = null;
        Log.d("MyForegroundService", "Service instance stopped and nulled.");
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Macrofy Service",
                    NotificationManager.IMPORTANCE_LOW // 중요도를 낮춰 소리/진동 최소화
            );
            channel.setDescription("AI Macrofy is running background tasks.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        // Create an Intent for the stop action
        Intent stopIntent = new Intent(this, MyForegroundService.class);
        stopIntent.setAction(ACTION_STOP_MACRO);
        // FLAG_IMMUTABLE is required for Android 12 (S) and above
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags);


        String providerText = currentAiProviderName != null ? currentAiProviderName : "AI";
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Macrofy Active")
                .setContentText("Macro running with " + providerText + " provider.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                // Add the stop action button
                .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent) // Replace with a real stop icon if available
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
        stopForeground(true);
        instance = null;
        super.onDestroy();
        Log.d("MyForegroundService", "MyForegroundService fully destroyed.");
    }
}