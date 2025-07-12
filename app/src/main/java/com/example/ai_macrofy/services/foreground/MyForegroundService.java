package com.example.ai_macrofy.services.foreground;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap; // Import Bitmap
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.llm.gemma.GemmaManager;
import com.example.ai_macrofy.llm.gemma.InitializationCallback;
import com.example.ai_macrofy.llm.gpt.GPTManager;
import com.example.ai_macrofy.llm.gemini.GeminiManager;
import com.example.ai_macrofy.services.accessibility.LayoutAccessibilityService;
import com.example.ai_macrofy.services.accessibility.MacroAccessibilityService;
import com.example.ai_macrofy.ui.PermissionRequestActivity;
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


    private final Long actionFailureRetryDelay = 200L; // 액션 실패 시 재시도 전 대기 시간
    private static final int MAX_SERVICE_CHECK_ATTEMPTS= 10; // 10 * 500ms = 5 seconds
    private static final long SERVICE_CHECK_INTERVAL_MS = 500;
    private static final long CHAT_HISTORY_SIZE_LIMIT=100;
    private static final long MIN_REQUEST_INTERVAL_MS = 500L; // 0.5초 룰
    private final Long instructionInterval = 100L; // LLM 호출 간 기본 간격

    private Handler mainHandler;
    private Handler timerHandler;
    public static boolean isMacroRunning = false;
    private long lastRequestTimestamp = 0;

    private List<Pair<String, String>> actionHistoryForRepetitionCheck;
    private List<ChatMessage> chatHistory;

    private String currentApiKey;
    private String currentBaseSystemPrompt;
    private String currentUserCommand; // 최초 사용자 명령
    private String currentAiProviderName;
    private AiModelService currentAiModelService;
    // initialScreenshot and initialScreenText are no longer needed as all flows use MediaProjection
    // private Bitmap initialScreenshot;
    // private String initialScreenText;

    private AppPreferences appPreferences;

    // MediaProjection related fields
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;


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

        // Get screen metrics
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            screenWidth = windowManager.getCurrentWindowMetrics().getBounds().width();
            screenHeight = windowManager.getCurrentWindowMetrics().getBounds().height();
            // Density is needed for MediaProjection
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            screenDensity = metrics.densityDpi;
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
        }
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

        // If macro is already running, we ignore new start commands for now.
        if (isMacroRunning) {
            Log.w("MyForegroundService", "Macro is already running. Ignoring new start command.");
            return START_STICKY;
        }

        // Start the service in the foreground immediately.
        // This is crucial to satisfy the requirement for MediaProjection.
        startForegroundNotification();

        currentApiKey = intent.getStringExtra("apiKey");
        currentBaseSystemPrompt = intent.getStringExtra("baseSystemPrompt");
        currentUserCommand = intent.getStringExtra("userCommand"); // 최초 사용자 명령 저장
        currentAiProviderName = intent.getStringExtra("ai_provider");


        // Check for MediaProjection data
        if (intent.hasExtra("media_projection_result_code")) {
            Log.d("MyForegroundService", "Received MediaProjection token.");
            int resultCode = intent.getIntExtra("media_projection_result_code", 0);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resultData = intent.getParcelableExtra("media_projection_result_data", Intent.class);
            } else {
                resultData = intent.getParcelableExtra("media_projection_result_data");
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData);
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        Log.w("MyForegroundService", "MediaProjection stopped by user or system.");
                        if (isMacroRunning) {
                            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Screen capture was stopped.", Toast.LENGTH_SHORT).show());
                            stopMacroExecution();
                        }
                    }
                }, mainHandler);
                // Setup VirtualDisplay and ImageReader right after getting MediaProjection
                setupVirtualDisplay();
            } else {
                Log.e("MyForegroundService", "Invalid MediaProjection data received.");
                Log.e("MyForegroundService", "MediaProjection token: " + resultCode + ", MediaProjection data: " + resultData);
                stopSelfAppropriately();
                return START_NOT_STICKY;
            }
        } else {
            // MediaProjection is now mandatory for starting the service.
            Log.e("MyForegroundService", "MediaProjection data not found in intent. Stopping service.");
            stopSelfAppropriately();
            return START_NOT_STICKY;
        }

        if (currentAiProviderName == null || currentAiProviderName.isEmpty()) {
            currentAiProviderName = appPreferences.getAiProvider();
            Log.w("MyForegroundService", "AI Provider missing in intent, loaded from prefs: " + currentAiProviderName);
        }
        if (currentApiKey == null || currentApiKey.isEmpty()) {
            currentApiKey = appPreferences.getApiKeyForCurrentProvider();
            Log.w("MyForegroundService", "API Key missing in intent, loaded from prefs for " + currentAiProviderName);
        }


        if ((currentApiKey == null || currentApiKey.isEmpty()) && !AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentAiProviderName)) {
            Log.e("MyForegroundService", "API Key is missing for a remote provider. Stopping service.");
            stopSelfAppropriately();
            return START_NOT_STICKY;
        }

        if (currentBaseSystemPrompt == null || currentBaseSystemPrompt.isEmpty() ||
                currentUserCommand == null || currentUserCommand.isEmpty() ||
                currentAiProviderName == null || currentAiProviderName.isEmpty()) {
            Log.e("MyForegroundService", "Essential data (Prompt, User Command, Provider) is missing. Stopping service.");
            stopSelfAppropriately();
            return START_NOT_STICKY;
        }

        isMacroRunning = true;
        actionHistoryForRepetitionCheck.clear();
        chatHistory.clear();
        updateNotification("Macro starting...");
        Log.d("MyForegroundService", "Starting new macro task sequence for provider: " + currentAiProviderName + " with command: " + currentUserCommand);
        initializeAiServiceAndStart();

        return START_STICKY;
    }

    private void initializeAiServiceAndStart() {
        if (AppPreferences.PROVIDER_GEMINI.equals(currentAiProviderName)) {
            currentAiModelService = new GeminiManager();
            currentAiModelService.setApiKey(currentApiKey);
            currentAiModelService.setContext(this); // Pass context for debug image saving
            // Gemini is remote, no special initialization needed, proceed directly.
            checkServicesAndStartMacro(0);
        } else if (AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentAiProviderName)) {
            GemmaManager gemmaManager = GemmaManager.getInstance(this);
            currentAiModelService = gemmaManager;
            currentAiModelService.setApiKey(currentApiKey); // Does nothing but good practice
            currentAiModelService.setContext(this);

            Log.d("MyForegroundService", "Preparing local Gemma model...");
            gemmaManager.prepare(new InitializationCallback() {
                @Override
                public void onInitSuccess() {
                    Log.d("MyForegroundService", "Gemma model initialized successfully.");
                    // Now that Gemma is ready, check for accessibility services and start.
                    checkServicesAndStartMacro(0);
                }

                @Override
                public void onInitFailure(String error) {
                    Log.e("MyForegroundService", "Gemma model initialization failed: " + error);
                    mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Gemma model failed to load. Stopping macro.", Toast.LENGTH_LONG).show());
                    stopMacroExecution();
                }
            });
        } else { // OpenAI
            currentAiModelService = new GPTManager();
            currentAiModelService.setApiKey(currentApiKey);
            currentAiModelService.setContext(this);
            // OpenAI is remote, no special initialization needed, proceed directly.
            checkServicesAndStartMacro(0);
        }
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

        long timeSinceLastRequest = System.currentTimeMillis() - lastRequestTimestamp;
        long remainingTimeForInterval = MIN_REQUEST_INTERVAL_MS - timeSinceLastRequest;

        // 기본 지연 시간과 0.5초 룰에 따른 최소 대기 시간 중 더 긴 시간을 선택합니다.
        long finalDelay = Math.max(delayMillis, remainingTimeForInterval);

        if (finalDelay > delayMillis) {
            Log.d("MyForegroundService", "Enforcing 0.5s rule. Additional delay: " + (finalDelay - delayMillis) + "ms");
        }

        timerHandler.postDelayed(() -> {
            if (!isMacroRunning) {
                Log.d("MyForegroundService", "Macro stopped during delay, not performing step.");
                return;
            }
            performSingleMacroStep();
        }, finalDelay);
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

        // The flow is now unified. Always capture a new screenshot.
        if (mediaProjection != null && imageReader != null) {
            captureScreenshotAndContinue();
        } else {
            Log.e("MyForegroundService", "No screenshot method available (MediaProjection or ImageReader is null). Stopping macro.");
            addExecutionFeedbackToHistory("Internal Error: No screenshot method available (MediaProjection not started or ImageReader not ready).");
            stopMacroExecution();
        }
    }

    private void setupVirtualDisplay() {
        if (mediaProjection == null) {
            Log.e("MyForegroundService", "setupVirtualDisplay called but mediaProjection is null.");
            return;
        }
        // Close existing ImageReader if it exists
        if (imageReader != null) {
            imageReader.close();
        }
        // Release existing VirtualDisplay if it exists
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        Log.d("MyForegroundService", "Setting up VirtualDisplay and ImageReader.");
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
    }

    private void captureScreenshotAndContinue() {
        Image image = null;
        Bitmap bitmap = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image != null) {
                Image.Plane[] planes = image.getPlanes();
                java.nio.ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidth;

                bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(buffer);
                // Crop the padding
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);

                Log.d("MyForegroundService", "Screenshot captured successfully.");

                String screenText = "Could not get screen layout.";
                if (LayoutAccessibilityService.instance != null) {
                    JSONObject layout = LayoutAccessibilityService.instance.extractLayoutInfo();
                    if (layout != null) {
                        screenText = layout.toString();
                    }
                }
                sendRequestToModel(null, bitmap, screenText);
            } else {
                Log.w("MyForegroundService", "acquireLatestImage() returned null. Retrying.");
                reportActionCompleted(false, "Failed to capture screenshot: acquireLatestImage() is null.");
            }
        } catch (Exception e) {
            Log.e("MyForegroundService", "Error capturing screenshot", e);
            reportActionCompleted(false, "Failed to capture screenshot: " + e.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
            // Do NOT release virtualDisplay or close imageReader here, as they are reused.
        }
    }

    private void sendRequestToModel(@Nullable String jsonLayout, @Nullable android.graphics.Bitmap bitmap, @Nullable String screenText) {
        String commandForLlm = currentUserCommand;

        // 0.5초 룰: AI 모델에 요청을 보내기 직전에 타임스탬프를 기록합니다.
        lastRequestTimestamp = System.currentTimeMillis();
        Log.d("MyForegroundService", "Setting lastRequestTimestamp: " + lastRequestTimestamp);

        currentAiModelService.generateResponse(
                currentBaseSystemPrompt,
                new ArrayList<>(chatHistory),
                jsonLayout, // No longer used
                bitmap,     // The screenshot
                screenText, // The screen text
                commandForLlm, // previousActionContext is no longer needed
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

                        // For history, we need a text representation of the input
                        String inputContextForHistory;
                        if (bitmap != null) {
                            inputContextForHistory = "[SCREENSHOT] + [SCREEN_LAYOUT_JSON] + User Command: " + commandForLlm;
                        } else {
                            // This path is less likely now
                            inputContextForHistory = "User Command: " + commandForLlm + "\n" +
                                    "Current Screen Layout JSON:\n" + (jsonLayout != null ? jsonLayout : "{}");
                        }


                        chatHistory.add(new ChatMessage("user", inputContextForHistory));
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
        // Release all resources related to MediaProjection
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
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
                .setContentText("Macro is starting...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                // Add the stop action button
                .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent) // Replace with a real stop icon if available
                .build();

        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.d("MyForegroundService", "Service started in foreground with text: " + "Macro is starting...");
        } catch (Exception e) {
            Log.e("MyForegroundService", "Error starting foreground service: " + e.getMessage(), e);
        }
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent stopIntent = new Intent(this, MyForegroundService.class);
        stopIntent.setAction(ACTION_STOP_MACRO);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AI Macrofy Active")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .addAction(R.drawable.ic_launcher_foreground, "Stop", stopPendingIntent)
                .build();

        manager.notify(NOTIFICATION_ID, notification);
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
        // Ensure all resources are released on destruction
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        stopForeground(true);
        instance = null;
        super.onDestroy();
        Log.d("MyForegroundService", "MyForegroundService fully destroyed.");
    }
}