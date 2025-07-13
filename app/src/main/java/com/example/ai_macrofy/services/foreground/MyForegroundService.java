package com.example.ai_macrofy.services.foreground;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.content.IntentFilter;
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
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.llm.gemma.GemmaManager;
import com.example.ai_macrofy.llm.gemma.InitializationCallback;
import com.example.ai_macrofy.llm.gpt.GPTManager;
import com.example.ai_macrofy.llm.gemini.GeminiManager;
import com.example.ai_macrofy.llm.gemini_web.GeminiWebManager;
import com.example.ai_macrofy.services.accessibility.LayoutAccessibilityService;
import com.example.ai_macrofy.services.accessibility.MacroAccessibilityService;
import com.example.ai_macrofy.ui.PermissionRequestActivity;
import com.example.ai_macrofy.ui.WebViewActivity;
import com.example.ai_macrofy.utils.AppPreferences;
import com.example.ai_macrofy.utils.SharedWebViewManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyForegroundService extends Service {

    // --- 추가: 페이지 로딩 리스너 인터페이스 ---
    public interface PageLoadListener {
        void onPageFinished(WebView view, String url);
    }
    private PageLoadListener pageLoadListener;

    public static volatile MyForegroundService instance;
    private final String CHANNEL_ID = "com.example.ai_macrofy.foreground_channel_v2";
    private final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP_MACRO = "com.example.ai_macrofy.ACTION_STOP_MACRO";


    private final Long actionFailureRetryDelay = 200L; // 액션 실패 시 재시도 전 대기 시간
    private static final int MAX_SERVICE_CHECK_ATTEMPTS= 10; // 10 * 500ms = 5 seconds
    private static final long SERVICE_CHECK_INTERVAL_MS = 500;
    private static final int CHAT_HISTORY_SIZE_LIMIT = 10; // 이전 100에서 수정. 대화 기록 크기를 제한하여 프롬프트 길이와 응답 시간을 관리합니다.
    private static final long MIN_REQUEST_INTERVAL_MS = 500L; // 0.5초 룰
    private final Long instructionInterval = 100L; // LLM 호출 간 기본 간격

    private Handler mainHandler;
    private Handler timerHandler;
    public static boolean isMacroRunning = false;
    private long lastRequestTimestamp = 0;

    private List<Pair<String, String>> actionHistoryForRepetitionCheck;
    private List<ChatMessage> chatHistory;

    // --- Add failure tracking fields ---
    private int consecutiveFailureCount = 0;
    private String lastFailureFeedback = "";
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    // --- End of added fields ---

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

    // --- WebView for background automation ---
    private WebView backgroundWebView; // 이제 공유 WebView를 가리킵니다.
    private Handler mainThreadHandler;
    private BroadcastReceiver loginSuccessReceiver;
    private WindowManager windowManager;
    private volatile boolean isWebViewAttached = false;
    private volatile String webViewAttachmentError = null; // --- 추가: WebView 연결 오류 메시지 저장 ---


    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        timerHandler = new Handler(Looper.getMainLooper());
        actionHistoryForRepetitionCheck = new ArrayList<>();
        chatHistory = new ArrayList<>();
        appPreferences = new AppPreferences(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Log.d("MyForegroundService", "onCreate");

        // --- WebView 생성 및 설정 ---
        mainThreadHandler = new Handler(Looper.getMainLooper());
        mainThreadHandler.post(() -> {
            Log.d("MyForegroundService", "Getting shared WebView on main thread.");
            // SharedWebViewManager에서 WebView 인스턴스를 가져옵니다.
            if(backgroundWebView == null) {
                Log.d("MyForegroundService", "Getting new WebView instance."+SharedWebViewManager.isIsInitialized()+" available on shared:"+SharedWebViewManager.isWebViewAvailable()+ "  is Ready?"+SharedWebViewManager.isReady());
                backgroundWebView = SharedWebViewManager.getWebView(this);
            }
            setupBackgroundWebView();
            // 백그라운드 작업을 위해 WebView를 보이지 않는 창에 연결
            attachWebViewToWindow();
        });

        // --- 로그인 성공 BroadcastReceiver 등록 ---
        loginSuccessReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (isMacroRunning) {
                    Log.d("MyForegroundService", "Received ACTION_GEMINI_LOGIN_SUCCESS. Resuming macro step.");
                    // 이제 WebView 객체 자체가 공유되므로, 쿠키 동기화나 reload가 필요 없습니다.
                    // 단순히 멈췄던 매크로 단계를 다시 시도합니다.
                    scheduleNextMacroStep(500); // 약간의 딜레이 후 재시도
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
                loginSuccessReceiver, new IntentFilter(WebViewActivity.ACTION_GEMINI_LOGIN_SUCCESS)
        );

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

    @SuppressLint("SetJavaScriptEnabled")
    private void setupBackgroundWebView() {
        if (backgroundWebView == null) {
            // 서비스 시작 시점에 WebView가 아직 생성되지 않았을 수 있으므로, 잠시 후 다시 시도합니다.
            mainThreadHandler.postDelayed(this::setupBackgroundWebView, 100);
            return;
        }
        // WebView 설정은 SharedWebViewManager에서 이미 수행되었으므로, 여기서는 WebViewClient만 설정합니다.
        backgroundWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Log.d("MyForegroundService", "Background WebView page finished loading: " + url); // --- 이 줄을 삭제합니다 ---
                // --- 추가: 리스너가 있으면 로딩 완료 알림 ---
                if (pageLoadListener != null) {
                    pageLoadListener.onPageFinished(view, url);
                }
            }
        });
    }

    // --- 추가: WebView를 보이지 않는 창에 연결하는 메서드 ---
    private void attachWebViewToWindow() {
        if (backgroundWebView == null) {
            Log.d("MyForegroundService", "WebView is null. Skipping attachment to window.");
            return;
        }

        if (backgroundWebView.getParent() != null) {
            // 이미 다른 뷰에 연결되어 있거나(예: WebViewActivity) WebView가 준비되지 않은 경우
            isWebViewAttached = true; // 이미 붙어있으면 준비된 것으로 간주
            Log.d("MyForegroundService", "WebView is already attached to a parent. Marking as ready and skipping new attachment.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.e("MyForegroundService", "Overlay permission not granted. Cannot attach WebView to window.");
            mainHandler.post(() -> Toast.makeText(this, "Overlay permission needed for background web tasks.", Toast.LENGTH_LONG).show());
            return;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1, // 1x1 픽셀
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSPARENT
        );
        params.gravity = Gravity.START | Gravity.TOP;
        params.x = 0;
        params.y = 0;

        try {
            windowManager.addView(backgroundWebView, params);
            isWebViewAttached = true; // 성공적으로 추가됨
            Log.d("MyForegroundService", "Attached background WebView to an invisible window.");
        } catch (Exception e) {
            Log.e("MyForegroundService", "Error attaching WebView to window", e);
            isWebViewAttached = false; // 실패
            webViewAttachmentError = e.getMessage(); // --- 추가: 오류 메시지 저장 ---
        }
    }

    // --- 추가: 보이지 않는 창에서 WebView를 제거하는 메서드 ---
    private void removeWebViewFromWindow() {
        if (backgroundWebView != null && backgroundWebView.isAttachedToWindow() && !(backgroundWebView.getParent() instanceof ViewGroup)) {
            // 뷰가 창에 연결되어 있고 부모가 ViewGroup이 아니라면, WindowManager가 관리하는 뷰로 간주합니다.
            try {
                windowManager.removeView(backgroundWebView);
                isWebViewAttached = false; // 제거됨
                Log.d("MyForegroundService", "Removed background WebView from invisible window.");
            } catch (Exception e) {
                Log.e("MyForegroundService", "Error removing WebView from window", e);
            }
        }
    }

    // --- 추가: WebView 준비 상태 확인 메서드 ---
    public boolean isWebViewReady() {
        return backgroundWebView != null && isWebViewAttached;
    }

    // --- 추가: 페이지 로딩 리스너 설정 메서드 ---
    public void setPageLoadListener(PageLoadListener listener) {
        this.pageLoadListener = listener;
    }

    // GeminiWebManager에서 호출할 메서드들
    public void loadUrlInBackground(String url) {
        mainThreadHandler.post(() -> {
            if (backgroundWebView != null) {
                Log.d("MyForegroundService", "Loading URL in background WebView: " + url);
                backgroundWebView.loadUrl(url);
            }
        });
    }

    public void evaluateJavascriptInBackground(String script, ValueCallback<String> callback) {
        mainThreadHandler.post(() -> {
            if (backgroundWebView != null) {
                backgroundWebView.evaluateJavascript(script, callback);
            } else {
                callback.onReceiveValue(null); // WebView가 없으면 null 반환
            }
        });
    }

    // --- 추가: JavaScript에서 Android로 결과를 비동기적으로 보내기 위한 콜백 인터페이스 ---
    public interface JavaScriptCallback {
        void onResult(String result);
    }

    // --- 추가: JavaScript에서 window.androidCallback()을 호출하면 결과를 받는 메서드 ---
    @SuppressLint("JavascriptInterface")
    public void evaluateJavascriptWithCallback(String script, JavaScriptCallback callback) {
        mainThreadHandler.post(() -> {
            if (backgroundWebView != null) {
                // 1. 자바스크립트 인터페이스 추가
                backgroundWebView.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void postMessage(String result) {
                        // JavaScript에서 보낸 결과를 메인 스레드에서 처리
                        mainThreadHandler.post(() -> {
                            // 사용 후 즉시 인터페이스 제거
                            backgroundWebView.removeJavascriptInterface("androidCallbackBridge");
                            callback.onResult(result);
                        });
                    }
                }, "androidCallbackBridge");

                // 2. window.androidCallback 함수를 정의하고, 원래 스크립트를 실행하는 래퍼 스크립트 생성
                String wrapperScript = "window.androidCallback = function(result) { androidCallbackBridge.postMessage(result); };" + script;
                backgroundWebView.evaluateJavascript(wrapperScript, null); // 결과는 콜백으로 오므로 여기서는 null
            } else {
                callback.onResult("ERROR: WebView is not available.");
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("MyForegroundService", "onStartCommand received");

        // 서비스 시작 시 WebView 활성화
        SharedWebViewManager.onResume();

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

        // --- Reset failure counter on new macro start ---
        consecutiveFailureCount = 0;
        lastFailureFeedback = "";
        // --- End of reset ---

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


        if ((currentApiKey == null || currentApiKey.isEmpty()) && !AppPreferences.PROVIDER_GEMMA_LOCAL.equals(currentAiProviderName) && !AppPreferences.PROVIDER_GEMINI_WEB.equals(currentAiProviderName)) {
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
        } else if (AppPreferences.PROVIDER_GEMINI_WEB.equals(currentAiProviderName)) {
            // --- 수정: WebView 사용 불가 시 재초기화 시도 로직 추가 ---
            if (!SharedWebViewManager.isWebViewAvailable()) {
                Log.w("MyForegroundService", "WebView was not available. Attempting to re-initialize...");
                SharedWebViewManager.init(this); // 서비스 컨텍스트로 재초기화 시도
                // 재초기화가 비동기로 처리되므로, 잠시 후 다시 확인
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!SharedWebViewManager.isWebViewAvailable()) {
                        Log.e("MyForegroundService", "WebView re-initialization failed. Cannot use Gemini Web provider.");
                        mainHandler.post(() -> Toast.makeText(getApplicationContext(), "WebView is not available on this device.", Toast.LENGTH_LONG).show());
                        stopMacroExecution();
                    } else {
                        Log.d("MyForegroundService", "WebView re-initialized successfully. Proceeding with macro.");
                        initializeAiServiceAndStart(); // 성공했으므로 다시 이 메서드를 호출하여 정상 로직 진행
                    }
                }, 1000); // 1초 대기 후 확인
                return; // 재초기화 시도 중에는 여기서 반환
            }

            currentAiModelService = new GeminiWebManager();
            currentAiModelService.setContext(this);
            // Web UI automation doesn't need an API key or special initialization.
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
        boolean webViewCheckNeeded = AppPreferences.PROVIDER_GEMINI_WEB.equals(currentAiProviderName);

        // --- 추가: WebView 연결에 명시적인 오류가 발생했는지 먼저 확인 ---
        if (webViewCheckNeeded && webViewAttachmentError != null) {
            Log.e("MyForegroundService", "Failing macro due to WebView attachment error: " + webViewAttachmentError);
            // 사용자에게 더 명확한 피드백 제공
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "WebView setup failed. Check 'Draw over other apps' permission.", Toast.LENGTH_LONG).show());
            stopMacroExecution(); // 매크로를 즉시 중단
            return;
        }

        // --- 수정: gemini_web 제공자일 경우 WebView 준비 상태도 함께 확인 ---
        boolean webViewIsReady = !webViewCheckNeeded || isWebViewReady();

        if (LayoutAccessibilityService.instance == null || MacroAccessibilityService.instance == null || !webViewIsReady) {
            if (attempt < MAX_SERVICE_CHECK_ATTEMPTS) {
                String reason = "";
                if (LayoutAccessibilityService.instance == null || MacroAccessibilityService.instance == null) {
                    reason += "Accessibility services not ready. ";
                }
                if (!webViewIsReady) {
                    reason += "WebView not ready. ";
                }
                Log.w("MyForegroundService", reason + "Attempt " + (attempt + 1) + "/" + MAX_SERVICE_CHECK_ATTEMPTS + ". Retrying in " + SERVICE_CHECK_INTERVAL_MS + "ms.");
                timerHandler.postDelayed(() -> checkServicesAndStartMacro(attempt + 1), SERVICE_CHECK_INTERVAL_MS);
            } else {
                String reason = "";
                if (LayoutAccessibilityService.instance == null || MacroAccessibilityService.instance == null) {
                    reason += "Accessibility Services not available. ";
                }
                if (!webViewIsReady) {
                    reason += "WebView not available. ";
                }
                Log.e("MyForegroundService", reason + "after " + (MAX_SERVICE_CHECK_ATTEMPTS * SERVICE_CHECK_INTERVAL_MS) + "ms. Stopping macro.");
                mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Required services not ready. Macro stopped.", Toast.LENGTH_LONG).show());
                isMacroRunning = false;
                stopSelfAppropriately();
            }
        } else {
            Log.d("MyForegroundService", "All required services are ready. Starting macro steps.");
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
            // --- 수정: acquireLatestImage() 실패 시 짧은 재시도 로직 추가 ---
            for (int i = 0; i < 3; i++) {
                image = imageReader.acquireLatestImage();
                if (image != null) {
                    break;
                }
                Log.w("MyForegroundService", "acquireLatestImage() returned null. Retrying... (" + (i + 1) + "/3)");
                try {
                    Thread.sleep(100); // 100ms 대기
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

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
                Log.e("MyForegroundService", "acquireLatestImage() returned null after retries. Failing action.");
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
        // 수정: 첫 요청인 경우에만 전체 사용자 명령을 전달하고, 그 이후에는 빈 문자열을 전달합니다.
        // 이렇게 하면 모델이 이전 행동의 맥락을 기반으로 다음 행동을 추론하게 됩니다.
        String commandForLlm = currentUserCommand; // --- 수정: 항상 최초 사용자 명령을 전달하도록 변경 ---

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

                        // Successful response, so reset the failure counter.
                        resetFailureCounter();

                        String pureJsonAction = null;

                        // --- 수정: startsWith() 검사를 제거하고, JSON 파싱을 먼저 시도하도록 로직 변경 ---
                        // 모든 응답에 대해 JSON 추출 및 파싱을 시도합니다.
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
                        // Gemini Web UI에서 오는 'JSON' 접두사 제거
                        if (cleanedResponse.startsWith("JSON")) {
                            cleanedResponse = cleanedResponse.substring(4);
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
                            Log.e("MyForegroundService", "Could not extract JSON from " + currentAiProviderName + " response after cleaning. Cleaned: " + rawResponse.substring(0, Math.min(rawResponse.length(), 200)));
                            // --- 수정: JSON 추출 실패 시, Gemini Web 제공자일 경우 피드백을 보내고 재시도 ---
                            if (AppPreferences.PROVIDER_GEMINI_WEB.equals(currentAiProviderName)) {
                                String feedback = "The previous response was not in the required JSON format. Your response MUST only be a valid JSON object. The invalid response was: " + rawResponse;
                                addExecutionFeedbackToHistory(feedback);
                            } else {
                                addExecutionFeedbackToHistory("LLM response parsing failed: Could not extract JSON. Cleaned response (first 200 chars): " + rawResponse.substring(0, Math.min(rawResponse.length(),200)));
                            }
                            scheduleNextMacroStep(actionFailureRetryDelay);
                            return;
                        }

                        // --- JSON 유효성 검사 및 재시도 로직 추가 ---
                        try {
                            JSONObject parsedJson = new JSONObject(pureJsonAction);
                            // 모델이 {"type":...} 와 같이 actions 배열을 생략하고 내용물만 반환했는지 확인
                            if (!parsedJson.has("actions") && parsedJson.has("type")) {
                                Log.w("MyForegroundService", "Response missing 'actions' wrapper. Reconstructing JSON.");
                                // actions 배열을 만들고 기존 객체를 추가
                                JSONArray actionsArray = new JSONArray();
                                actionsArray.put(parsedJson);
                                // 새로운 JSON 객체를 생성하여 actions 배열을 넣음
                                JSONObject reconstructedJson = new JSONObject();
                                reconstructedJson.put("actions", actionsArray);
                                pureJsonAction = reconstructedJson.toString();
                                Log.d("MyForegroundService", "Reconstructed JSON: " + pureJsonAction);
                            }
                        } catch (JSONException e) {
                            Log.e("MyForegroundService", "JSON parsing failed for extracted string: " + e.getMessage());
                            // 모델에게 수정을 요청하는 피드백을 추가합니다.
                            String feedback = "JSON parsing failed. The response was not valid JSON. Please correct the format and provide a valid JSON object. Error: " + e.getMessage() + ". The invalid response was: " + pureJsonAction;
                            addExecutionFeedbackToHistory(feedback);
                            // 즉시 다음 스텝을 예약하여 수정된 프롬프트로 재요청합니다.
                            scheduleNextMacroStep(actionFailureRetryDelay);
                            return; // 여기서 처리를 중단하고 재시도를 기다립니다.
                        }
                        // --- 로직 추가 끝 ---


                        // For history, we need a text representation of the input
                        String inputContextForHistory;
                        if (bitmap != null) {
                            // 수정: 첫 요청이 아닐 경우 User Command를 포함하지 않도록 수정합니다.
                            inputContextForHistory = "[SCREENSHOT] + User Command: " + commandForLlm;
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

                        // --- Add failure tracking for API errors ---
                        handleFailure("LLM API Error: " + error);
                        // --- End of failure tracking ---
                    }
                });
    }

    public void reportActionCompleted(boolean success, @Nullable String feedback) {
        if (!isMacroRunning) return;

        if (success) {
            Log.d("MyForegroundService", "Action reported as successful.");
            // Successful action, so reset the failure counter.
            resetFailureCounter();
            scheduleNextMacroStep(instructionInterval);
        } else {
            Log.e("MyForegroundService", "Action reported as failed: " + feedback);
            // --- Add failure tracking for action execution errors ---
            handleFailure(feedback != null ? feedback : "Unknown action execution error.");
            // --- End of failure tracking ---
        }
    }

    private void resetFailureCounter() {
        if (consecutiveFailureCount > 0) {
            Log.d("MyForegroundService", "Resetting failure counter.");
        }
        consecutiveFailureCount = 0;
        lastFailureFeedback = "";
    }

    private void handleFailure(String feedback) {
        if (feedback.equals(lastFailureFeedback)) {
            consecutiveFailureCount++;
            Log.w("MyForegroundService", "Consecutive failure " + consecutiveFailureCount + " with same feedback: " + feedback);
        } else {
            consecutiveFailureCount = 1;
            lastFailureFeedback = feedback;
            Log.w("MyForegroundService", "New failure type, resetting count to 1. Feedback: " + feedback);
        }

        if (consecutiveFailureCount >= MAX_CONSECUTIVE_FAILURES) {
            Log.e("MyForegroundService", "Max consecutive failures (" + MAX_CONSECUTIVE_FAILURES + ") reached. Stopping macro.");
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Macro stopped after " + MAX_CONSECUTIVE_FAILURES + " consecutive errors.", Toast.LENGTH_LONG).show());
            stopMacroExecution();
        } else {
            if (getApplicationContext() != null) {
                mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Action Failed: " + feedback + ". Retrying (" + consecutiveFailureCount + "/" + MAX_CONSECUTIVE_FAILURES + ")", Toast.LENGTH_LONG).show());
            }
            addExecutionFeedbackToHistory(feedback);
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

        // --- 추가: 현재 AI 서비스에 대한 정리 작업 호출 ---
        if (currentAiModelService != null) {
            Log.d("MyForegroundService", "Performing cleanup for provider: " + currentAiProviderName);
            currentAiModelService.cleanup();
        }

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
        isWebViewAttached = false; // 서비스 종료 시 플래그 리셋

        // 서비스 종료 시 WebView 일시정지
        SharedWebViewManager.onResume(); // onPause() 대신 onResume()을 호출하여 WebView가 활성 상태를 유지하도록 합니다.

        if (timerHandler != null) {
            timerHandler.removeCallbacksAndMessages(null);
        }

        // --- BroadcastReceiver 해제 ---
        if (loginSuccessReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(loginSuccessReceiver);
            loginSuccessReceiver = null;
        }

        // --- WebView 리소스 해제 (수정) ---
        // 서비스가 추가했던 보이지 않는 창에서 WebView를 제거합니다.
        mainThreadHandler.post(() -> {
            removeWebViewFromWindow();
            // --- 추가: WebView 상태를 완전히 초기화하여 다음 매크로 실행 시 문제를 방지합니다. ---
            if (backgroundWebView != null) {
                Log.d("MyForegroundService", "Resetting WebView state for next use.");
                backgroundWebView.stopLoading();
                backgroundWebView.loadUrl("about:blank"); // 빈 페이지로 리셋
                CookieManager.getInstance().removeAllCookies(null); // 모든 쿠키 삭제
                CookieManager.getInstance().flush();
            }
        });

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