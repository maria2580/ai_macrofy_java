package com.example.ai_macrofy.llm.gemini_web;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.services.foreground.MyForegroundService;
import com.example.ai_macrofy.utils.SharedWebViewManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeminiWebManager implements AiModelService, MyForegroundService.PageLoadListener {

    private static final String TAG = "GeminiWebManager";
    private static final String GEMINI_URL = "https://gemini.google.com";
    private MyForegroundService serviceContext;
    private ModelResponseCallback pendingCallback;
    // --- 수정: AtomicBoolean을 사용하여 스레드 안전성 확보 ---
    private final AtomicBoolean isCheckingLogin = new AtomicBoolean(false);
    private boolean isAwaitingLoginCheck = false;
    // --- 추가: 웹 자동화에 필요한 멤버 변수 ---
    private String systemInstruction;
    private List<ChatMessage> conversationHistory;
    private String currentUserVoiceCommand;


    @Override
    public void setApiKey(String apiKey) {
        // Not needed for web UI automation
    }

    @Override
    public void setContext(Context context) {
        if (context instanceof MyForegroundService) {
            this.serviceContext = (MyForegroundService) context;
            // 서비스에 PageLoadListener로 자신을 등록
            this.serviceContext.setPageLoadListener(this);
        } else {
            Log.e(TAG, "Context is not an instance of MyForegroundService!");
        }
    }

    @Override
    public void generateResponse(String systemInstruction, List<ChatMessage> conversationHistory, @Nullable String currentScreenLayoutJson, @Nullable Bitmap currentScreenBitmap, @Nullable String currentScreenText, String currentUserVoiceCommand, ModelResponseCallback callback) {
        Log.d(TAG, "generateResponse called for Gemini Web UI.");
        this.pendingCallback = callback;
        this.isAwaitingLoginCheck = true; // 로그인 확인 시작 플래그 설정

        // --- 추가: 웹 자동화를 위해 프롬프트 정보 저장 ---
        this.systemInstruction = systemInstruction;
        this.conversationHistory = conversationHistory;
        this.currentUserVoiceCommand = currentUserVoiceCommand;


        // 현재 WebView의 URL을 확인하여 불필요한 로딩을 방지합니다.
        WebView webView = SharedWebViewManager.getWebView();
        if (webView != null && webView.getUrl() != null && webView.getUrl().startsWith("https://gemini.google.com/app")) {
            Log.d(TAG, "Gemini app URL is already loaded. Skipping reload, proceeding to login check.");
            // 페이지가 이미 로드되었으므로, 바로 onPageFinished 로직을 실행합니다.
            onPageFinished(webView, webView.getUrl());
        } else {
            Log.d(TAG, "Requesting to load Gemini URL. Waiting for onPageFinished callback.");
            serviceContext.loadUrlInBackground(GEMINI_URL);
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        // --- 수정: 최종 목적지 URL에서만 로그인 확인을 시작하도록 변경 ---
        if (!url.startsWith("https://gemini.google.com/app")) {
            Log.d(TAG, "onPageFinished for non-final URL, ignoring: " + url);
            return;
        }

        // --- 수정: isAwaitingLoginCheck 플래그를 사용하여 단 한 번만 폴링을 시작하도록 보장 ---
        if (!isAwaitingLoginCheck) {
            Log.d(TAG, "onPageFinished received, but not awaiting a login check. Ignoring.");
            return; // 이 매니저가 시작한 확인 작업이 아니면 무시
        }
        // 확인 작업을 시작할 것이므로 플래그를 false로 설정하여 중복 실행 방지
        isAwaitingLoginCheck = false;

        Log.d(TAG, "onPageFinished received in manager. Starting login check polling.");

        // --- 수정: 폴링을 시작하기 전에 isCheckingLogin 플래그를 true로 설정합니다. ---
        isCheckingLogin.set(true);

        // 페이지 로딩이 완료되었으므로, 이제 로그인 상태를 폴링합니다.
        // --- 수정: 로그인 확인 스크립트를 이메일, 다국어 키워드를 포함하는 더 정확한 로직으로 교체 ---
        String checkLoginScript = "(function() { " +
                "const emailRegex = /\\S+@\\S+\\.\\S+/; " +
                "const accountElements = Array.from(document.querySelectorAll('[aria-label]')); " +
                "for (const el of accountElements) { " +
                "    if (emailRegex.test(el.getAttribute('aria-label'))) { return 'LOGGED_IN'; } " +
                "} " +
                "const loginKeywords = ['Sign in', '로그인']; " +
                "const loginButtons = Array.from(document.querySelectorAll('a, button')).find(el => { " +
                "    const label = el.getAttribute('aria-label') || el.textContent || ''; " +
                "    return loginKeywords.some(keyword => label.trim().includes(keyword)); " +
                "}); " +
                "if (loginButtons) { return 'NOT_LOGGED_IN'; } " +
                "if (window.location.href.includes('gemini.google.com/app')) { return 'LOGGED_IN'; } " +
                "return 'UNKNOWN'; " + // 로그인/아웃 상태를 명확히 알 수 없는 경우
                "})();;";

        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 5;
        final long delay = 1000L;
        final int[] currentAttempt = {0};

        handler.post(new Runnable() {
            @Override
            public void run() {
                // isCheckingLogin이 false로 바뀌면(예: 작업 취소) 루프 중단
                if (!isCheckingLogin.get()) {
                    Log.d(TAG, "Login check was cancelled. Stopping polling.");
                    return;
                }

                currentAttempt[0]++;
                Log.d(TAG, "Checking for login... Attempt " + currentAttempt[0] + "/" + maxAttempts);

                serviceContext.evaluateJavascriptInBackground(checkLoginScript, loginStatus -> {
                    if (!isCheckingLogin.get()) return; // 콜백이 도착하기 전에 작업이 취소된 경우

                    // --- 수정: 스크립트 결과("LOGGED_IN", "NOT_LOGGED_IN")에 따라 분기 처리 ---
                    if ("\"LOGGED_IN\"".equals(loginStatus)) {
                        Log.d(TAG, "User is logged in. Proceeding with web automation.");
                        isCheckingLogin.set(false);
                        // --- 수정: wait 액션 대신 실제 웹 자동화 수행 ---
                        performWebAutomation();

                    } else if ("\"NOT_LOGGED_IN\"".equals(loginStatus)) {
                        Log.w(TAG, "User is not logged in. Signaling service to request login.");
                        if (pendingCallback != null) {
                            pendingCallback.onError("LOGIN_REQUIRED");
                        }
                        isCheckingLogin.set(false);
                        pendingCallback = null;
                    } else { // "UNKNOWN" 또는 기타 경우
                        if (currentAttempt[0] < maxAttempts) {
                            Log.d(TAG, "Login status is not definitive ('" + loginStatus + "'). Retrying...");
                            handler.postDelayed(this, delay);
                        } else {
                            Log.w(TAG, "Could not determine login status after " + maxAttempts + " attempts. Assuming login is required.");
                            if (pendingCallback != null) {
                                pendingCallback.onError("LOGIN_REQUIRED");
                            }
                            isCheckingLogin.set(false);
                            pendingCallback = null;
                        }
                    }
                });
            }
        });
    }

    /**
     * 로그인 확인 후, 실제 Gemini 웹사이트와 상호작용하여 응답을 생성하고 추출합니다.
     */
    private void performWebAutomation() {
        if (pendingCallback == null) {
            Log.e(TAG, "Callback is null, aborting web automation.");
            return;
        }

        String finalPrompt = buildFinalPrompt(systemInstruction, conversationHistory, currentUserVoiceCommand);
        // JavaScript에서 사용할 수 있도록 프롬프트를 이스케이프 처리합니다.
        String escapedPrompt = JSONObject.quote(finalPrompt);

        // 1. 입력창에 프롬프트 입력
        // 2. 전송 버튼 클릭
        // 3. 잠시 대기 후 응답 추출
        // --- 수정: Gemini 웹 UI의 실제 HTML 구조에 맞게 입력창 선택자 변경 ---
        String automationScript = "(function() {" +
                "    const editorDiv = document.querySelector('div.ql-editor.new-input-ui');" +
                "    if (!editorDiv) { return 'ERROR: Editor div not found.'; }" +
                "    let pTag = editorDiv.querySelector('p');" +
                "    if (!pTag) { " +
                "        pTag = document.createElement('p');" +
                "        editorDiv.appendChild(pTag);" +
                "    }" +
                "    pTag.textContent = " + escapedPrompt + ";" +
                "    const sendButton = document.querySelector('button[aria-label=\"Send message\"]');" +
                "    if (!sendButton) { return 'ERROR: Send button not found.'; }" +
                "    sendButton.click();" +
                "    return 'SUBMITTED';" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(automationScript, result -> {
            if (!"\"SUBMITTED\"".equals(result)) {
                Log.e(TAG, "Web automation script failed: " + result);
                pendingCallback.onError("Web automation script failed: " + result);
                pendingCallback = null;
                return;
            }

            // 전송 후 응답이 생성될 때까지 잠시 대기 (3초)
            new Handler(Looper.getMainLooper()).postDelayed(this::extractResponse, 3000);
        });
    }

    private void extractResponse() {
        // 응답은 일반적으로 'response-container' 클래스 내부에 있습니다.
        // 가장 마지막 message-content를 찾습니다.
        String extractionScript = "(function() {" +
                "    const responseElements = document.querySelectorAll('.message-content .response-container');" +
                "    if (responseElements.length === 0) { return 'ERROR: Response container not found.'; }" +
                "    const lastResponse = responseElements[responseElements.length - 1];" +
                "    return lastResponse.textContent;" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(extractionScript, response -> {
            if (response == null || response.startsWith("\"ERROR:")) {
                Log.e(TAG, "Failed to extract response: " + response);
                pendingCallback.onError("Failed to extract response: " + response);
            } else {
                Log.d(TAG, "Extracted response from web: " + response);
                // 성공 콜백으로 추출된 텍스트(JSON이 아님)를 전달합니다.
                pendingCallback.onSuccess(response);
            }
            pendingCallback = null;
        });
    }

    private String buildFinalPrompt(String systemInstruction, List<ChatMessage> conversationHistory, String currentUserVoiceCommand) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(systemInstruction).append("\n\n");

        for (ChatMessage message : conversationHistory) {
            promptBuilder.append(message.role).append(": ").append(message.content).append("\n");
        }

        promptBuilder.append("user: ").append(processUserCommandForPrompt(currentUserVoiceCommand)).append("\n");
        promptBuilder.append("assistant:");

        return promptBuilder.toString();
    }


    @Override
    public String processUserCommandForPrompt(String userCommand) {
        return userCommand != null ? userCommand.trim() : "";
    }
}