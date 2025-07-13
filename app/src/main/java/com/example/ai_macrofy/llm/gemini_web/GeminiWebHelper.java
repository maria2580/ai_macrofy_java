package com.example.ai_macrofy.llm.gemini_web;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.services.foreground.MyForegroundService;
import com.example.ai_macrofy.utils.SharedWebViewManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean; // --- 추가 ---

public class GeminiWebHelper {

    private static final String TAG = "GeminiWebHelper";
    private static final String GEMINI_URL = "https://gemini.google.com";

    private final MyForegroundService serviceContext;
    private ModelResponseCallback pendingCallback;
    private String finalPrompt;
    private Bitmap pendingBitmap; // --- 추가: 비트맵 저장 ---
    private final AtomicBoolean isAwaitingSubmission = new AtomicBoolean(false); // --- 추가: 제출 대기 상태 플래그 ---

    public GeminiWebHelper(MyForegroundService serviceContext) {
        this.serviceContext = serviceContext;
        // --- PageLoadListener 설정 로직 제거 ---
    }

    public void generateResponse(String finalPrompt, @Nullable Bitmap bitmap, ModelResponseCallback callback) {
        logCurrentWebViewUrl(); // --- 추가: 현재 URL 로깅 ---
        this.finalPrompt = finalPrompt;
        this.pendingBitmap = bitmap;
        this.pendingCallback = callback;
        // this.isAwaitingSubmission.set(false); // --- 제거: 진행 중인 요청을 방해할 수 있으므로 상태를 여기서 초기화하지 않습니다. ---

        waitForWebViewAndProceed();
    }

    private void waitForWebViewAndProceed() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 25; // 5 seconds timeout
        final long delay = 200L;
        final int[] currentAttempt = {0};

        handler.post(new Runnable() {
            @Override
            public void run() {
                // --- 수정: SharedWebViewManager.isReady()를 직접 호출 ---
                if (SharedWebViewManager.isReady()) {
                    Log.d(TAG, "WebView is ready. Proceeding to check/load URL.");
                    // --- 수정: URL 확인 및 로딩 로직을 loadUrlAndCheckLogin으로 위임 ---
                    loadUrlAndCheckLogin();
                } else {
                    currentAttempt[0]++;
                    if (currentAttempt[0] < maxAttempts) {
                        Log.d(TAG, "WebView not ready yet. Waiting... Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, delay);
                    } else {
                        handleError("WebView initialization timed out.");
                    }
                }
            }
        });
    }

    // --- onPageFinished 메서드 제거 ---

    // --- 추가: URL 로딩과 로그인 확인을 통합한 새 메서드 ---
    private void loadUrlAndCheckLogin() {
        serviceContext.setPageLoadListener((view, url) -> {
            // --- 수정: URL 확인 조건을 완화하여 gemini.google.com 도메인 전체에 대해 반응하도록 변경 ---
            if (url.startsWith(GEMINI_URL)) {
                Log.d(TAG, "Gemini page finished loading: " + url + ". Checking login status.");
                serviceContext.setPageLoadListener(null); // 리스너를 즉시 제거하여 중복 호출 방지
                checkLoginStatus();
            }
        });
        // 항상 Gemini URL을 로드하여 일관된 시작점을 보장합니다.
        // WebView가 이미 해당 페이지에 있더라도 onPageFinished가 트리거됩니다.
        serviceContext.loadUrlInBackground(GEMINI_URL);
    }


    private void checkLoginStatus() {
        String checkLoginScript = "(function() { " +
                "const emailRegex = /\\S+@\\S+\\.\\S+/; " +
                "const accountElements = Array.from(document.querySelectorAll('[aria-label]')); " +
                "for (const el of accountElements) { if (emailRegex.test(el.getAttribute('aria-label'))) { return 'LOGGED_IN'; } } " +
                "const loginKeywords = ['Sign in', '로그인']; " +
                "const loginButtons = Array.from(document.querySelectorAll('a, button')).find(el => { " +
                "    const label = el.getAttribute('aria-label') || el.textContent || ''; " +
                "    return loginKeywords.some(keyword => label.trim().includes(keyword)); " +
                "}); " +
                "if (loginButtons) { return 'NOT_LOGGED_IN'; } " +
                "if (window.location.href.includes('gemini.google.com/app')) { return 'LOGGED_IN'; } " +
                "return 'UNKNOWN'; " +
                "})();";

        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 5;
        final int[] currentAttempt = {0};

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null && !isAwaitingSubmission.get()) { // --- 수정: 제출 대기 상태도 확인 ---
                    Log.d(TAG, "Login check stopped, callback is null and not awaiting submission.");
                    return;
                }
                currentAttempt[0]++;
                Log.d(TAG, "Checking for login... Attempt " + currentAttempt[0] + "/" + maxAttempts);
                serviceContext.evaluateJavascriptInBackground(checkLoginScript, loginStatus -> {
                    if (pendingCallback == null && !isAwaitingSubmission.get()) return; // --- 수정: 제출 대기 상태도 확인 ---

                    if ("\"LOGGED_IN\"".equals(loginStatus)) {
                        Log.d(TAG, "User is logged in. Proceeding to submit prompt.");
                        // --- 수정: isAwaitingSubmission 플래그 검사를 제거하고 submitPrompt를 직접 호출합니다. ---
                        // submitPrompt가 플래그 관리를 중앙에서 처리합니다.
                        submitPrompt(GeminiWebHelper.this.finalPrompt, GeminiWebHelper.this.pendingBitmap, GeminiWebHelper.this.pendingCallback);
                    } else if ("\"NOT_LOGGED_IN\"".equals(loginStatus)) {
                        handleError("LOGIN_REQUIRED");
                    } else {
                        if (currentAttempt[0] < maxAttempts) {
                            Log.d(TAG, "Login status is not definitive ('" + loginStatus + "'). Retrying...");
                            handler.postDelayed(this, 1000L);
                        } else {
                            Log.w(TAG, "Could not determine login status. Assuming login is required.");
                            handleError("LOGIN_REQUIRED");
                        }
                    }
                });
            }
        });
    }

    public void submitPrompt(String finalPrompt, @Nullable Bitmap bitmap, ModelResponseCallback callback) {
        // --- 수정: isAwaitingSubmission 플래그를 사용하여 중복 호출을 더 안정적으로 방지합니다. ---
        if (!isAwaitingSubmission.compareAndSet(false, true)) {
            Log.w(TAG, "WARNING: submitPrompt called while another request is pending or already in submission queue!");
            // --- 추가: 이전 요청이 멈춘 경우, 에러 처리하고 새 요청을 위해 상태를 리셋합니다. ---
            if (this.pendingCallback != null) {
                Log.e(TAG, "Force-failing previous stuck request.");
                handleError("New request arrived, cancelling the previous one.");
                // 재시도를 위해 잠시 후 다시 submitPrompt를 호출하도록 유도할 수 있으나,
                // 여기서는 일단 이전 요청을 정리하고 새 요청은 무시하여 시스템 안정을 우선합니다.
            }
            // --- 수정: 이미 다른 요청이 처리 중일 때, 현재 콜백을 덮어쓰지 않고 즉시 반환합니다. ---
            if (this.pendingCallback != callback) {
                 Log.w(TAG, "A different callback was provided. The original request will proceed. The new request is ignored.");
            }
            return;
        }
        Log.d(TAG, "submitPrompt called. Setting new callback and proceeding.");
        
        // --- 수정: 파라미터 설정 로직을 플래그 확인 후로 이동 ---
        this.finalPrompt = finalPrompt;
        this.pendingBitmap = bitmap;
        this.pendingCallback = callback; // --- 수정: submitPrompt에서도 콜백을 설정하도록 복원 ---

        if (pendingBitmap != null) {
            Log.d("GeminiWebHelper", "Submitting prompt with image.");
            // --- 수정: 파일 업로드 대신 붙여넣기(paste) 방식으로 변경 ---
            // 1. 비트맵에 격자를 그리고 Base64 문자열로 변환
            Bitmap bitmapWithGrid = drawGridOnBitmap(pendingBitmap);
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            bitmapWithGrid.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            String base64Image = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP);

            // 2. Base64 이미지를 사용하여 붙여넣기 이벤트를 시뮬레이션하는 JavaScript 생성
            String pasteScript = "(function() {" +
                "    function findElement(selector, root = document.body) {" +
                "        const element = root.querySelector(selector);" +
                "        if (element) return element;" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            const found = findElement(selector, shadowRoot);" +
                "            if (found) return found;" +
                "        }" +
                "        return null;" +
                "    }" +
                "    const target = findElement('rich-textarea .ql-editor');" +
                "    if (!target) { return 'ERROR: Prompt input area (.ql-editor) not found.'; }" +
                "    const base64Data = '" + base64Image + "';" +
                "    try {" +
                "        const byteCharacters = atob(base64Data);" +
                "        const byteNumbers = new Array(byteCharacters.length);" +
                "        for (let i = 0; i < byteCharacters.length; i++) {" +
                "            byteNumbers[i] = byteCharacters.charCodeAt(i);" +
                "        }" +
                "        const byteArray = new Uint8Array(byteNumbers);" +
                "        const blob = new Blob([byteArray], {type: 'image/png'});" +
                "        const dataTransfer = new DataTransfer();" +
                "        dataTransfer.items.add(new File([blob], 'screenshot.png', {type: 'image/png'}));" +
                "        const pasteEvent = new ClipboardEvent('paste', { clipboardData: dataTransfer, bubbles: true, cancelable: true });" +
                "        target.dispatchEvent(pasteEvent);" +
                "        return 'PASTE_EVENT_DISPATCHED';" + // 수정: window.androidCallback 대신 직접 반환
                "    } catch (e) { return 'ERROR: ' + e.message; }" + // 수정: window.androidCallback 대신 직접 반환
                "})();";

            // --- 수정: 스크립트 실행과 타임아웃을 자체 핸들러에서 관리하여 안정성 확보 ---
            new Handler(Looper.getMainLooper()).post(() -> {
                // Paste 이벤트 타임아웃 핸들러
                final Handler timeoutHandler = new Handler(Looper.getMainLooper());
                final Runnable timeoutRunnable = () -> {
                    Log.e(TAG, "Timeout waiting for paste event callback. The operation may be stuck.");
                    handleError("Timeout: Failed to get a response from the paste event script.");
                };
                timeoutHandler.postDelayed(timeoutRunnable, 15000); // 15초 타임아웃

                // 수정: evaluateJavascriptWithCallback 대신 evaluateJavascriptInBackground 사용
                serviceContext.evaluateJavascriptInBackground(pasteScript, result -> {
                    // 타임아웃 콜백 제거
                    timeoutHandler.removeCallbacks(timeoutRunnable);

                    // 결과 문자열에서 따옴표 제거
                    String cleanedResult = result != null ? result.replace("\"", "") : "";

                    if ("PASTE_EVENT_DISPATCHED".equals(cleanedResult)) {
                        Log.d(TAG, "Paste event dispatched successfully.");
                        // 이미지 썸네일이 나타날 때까지 폴링
                        pollForImageAndSubmitPrompt();
                    } else {
                        handleError("Failed to dispatch paste event: " + cleanedResult);
                    }
                });
            });
        } else {
            // 이미지가 없는 경우
            Log.d("GeminiWebHelper", "Submitting prompt without image.");
            submitTextAndSend();
        }
    }

    private void pollForImageAndSubmitPrompt() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 25; // 12.5초 타임아웃
        final int[] currentAttempt = {0};

        // --- 수정: Shadow DOM을 재귀적으로 탐색하고 명확한 상태를 반환하는 스크립트로 변경 ---
        final String checkImageScript = "(function() {" +
                "    function findElement(selector, root = document.body) {" +
                "        const element = root.querySelector(selector);" +
                "        if (element) return element;" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            const found = findElement(selector, shadowRoot);" +
                "            if (found) return found;" +
                "        }" +
                "        return null;" +
                "    }" +
                "    const preview = findElement('uploader-file-preview');" + // 수정: 더 일반적인 선택자 사용
                "    if (!preview) return 'NOT_FOUND';" +
                "    const loadingElement = findElement('.loading', preview.shadowRoot || preview);" + // preview 내부에서 loading 탐색
                "    if (loadingElement) return 'UPLOADING';" +
                "    return 'COMPLETED';" +
                "})();";

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return; // 작업 취소됨
                currentAttempt[0]++;
                serviceContext.evaluateJavascriptInBackground(checkImageScript, result -> {
                    // --- 수정: 3가지 상태(COMPLETED, UPLOADING, NOT_FOUND)를 처리하도록 로직 개선 ---
                    if ("\"COMPLETED\"".equals(result)) {
                        Log.d(TAG, "Image upload completed. Proceeding to submit prompt.");
                        submitTextAndSend();
                    } else if (currentAttempt[0] < maxAttempts) {
                        Log.d(TAG, "Waiting for image upload... Status: " + (result != null ? result : "null") + ", Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, 500L);
                    } else {
                        handleError("Timed out waiting for image upload to complete. Final status: " + result);
                    }
                });
            }
        });
    }

    private void submitTextAndSend() {
        String escapedPrompt = JSONObject.quote(finalPrompt);
        // 수정: 전송 버튼을 찾는 로직을 재시도 기능과 함께 강화합니다.
        String automationScript = "(function() {" +
                "    function findElement(selector, root = document.body) {" +
                "        const element = root.querySelector(selector);" +
                "        if (element) return element;" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            const found = findElement(selector, shadowRoot);" +
                "            if (found) return found;" +
                "        }" +
                "        return null;" +
                "    }" +
                "    const editorDiv = findElement('rich-textarea .ql-editor');" +
                "    if (!editorDiv) { return 'ERROR: Editor div not found.'; }" +
                "    let pTag = editorDiv.querySelector('p');" +
                "    if (!pTag) { pTag = document.createElement('p'); editorDiv.appendChild(pTag); }" +
                "    pTag.appendChild(document.createTextNode(' ' + " + escapedPrompt + "));" +
                "    const maxAttempts = 10;" +
                "    let attempts = 0;" +
                "    function clickSendButton() {" +
                "        const sendButton = findElement('button[aria-label=\"Send message\"]:not([disabled]), button[aria-label=\"메시지 보내기\"]:not([disabled])');" +
                "        if (sendButton) {" +
                "            sendButton.click();" +
                "            window.androidCallbackForSubmit('SUBMITTED');" + // 콜백을 위한 가상 함수 호출
                "        } else if (attempts < maxAttempts) {" +
                "            attempts++;" +
                "            setTimeout(clickSendButton, 200);" +
                "        } else {" +
                "            window.androidCallbackForSubmit('ERROR: Send button not found or disabled.');" +
                "        }" +
                "    }" +
                "    clickSendButton();" +
                "    return 'ATTEMPTING_SUBMIT';" + // 스크립트가 즉시 반환하는 값
                "})();";

        // 비동기 스크립트의 실제 결과를 처리하기 위해 콜백 방식과 유사하게 구현합니다.
        // 여기서는 간단하게 evaluateJavascriptInBackground를 사용하고, 스크립트 내에서 콜백을 흉내 냅니다.
        // 실제 콜백을 구현하려면 MyForegroundService에 evaluateJavascriptWithCallback 같은 메서드가 필요합니다.
        // 지금은 스크립트가 반환하는 최종 상태를 신뢰하고 진행합니다.
        // 위 스크립트는 즉시 반환하므로, 실제 클릭 결과는 알 수 없습니다.
        // 따라서, 스크립트를 동기적으로 결과를 반환하도록 수정합니다.

        String finalAutomationScript = "(function() {" +
                "    function findElement(selector, root = document.body) {" +
                "        const element = root.querySelector(selector);" +
                "        if (element) return element;" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            const found = findElement(selector, shadowRoot);" +
                "            if (found) return found;" +
                "        }" +
                "        return null;" +
                "    }" +
                "    const editorDiv = findElement('rich-textarea .ql-editor');" +
                "    if (!editorDiv) { return 'ERROR: Editor div not found.'; }" +
                "    const newPromptParagraph = document.createElement('p');" +
                "    newPromptParagraph.appendChild(document.createTextNode(" + escapedPrompt + "));" +
                "    editorDiv.appendChild(newPromptParagraph);" +
                "    setTimeout(() => {" +
                "        const sendButton = findElement('button[aria-label=\"Send message\"]:not([disabled]), button[aria-label=\"메시지 보내기\"]:not([disabled])');" +
                "        if (!sendButton) { console.error('ERROR: Send button not found or disabled.'); return; }" +
                "        sendButton.click();" +
                "    }, 100);" + // 텍스트 추가 후 버튼이 활성화될 시간을 벌기 위해 100ms 지연
                "    return 'SUBMITTED';" +
                "})();";


        // 텍스트 추가 후 버튼이 활성화될 시간을 벌기 위해 약간의 딜레이를 줍니다.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            serviceContext.evaluateJavascriptInBackground(finalAutomationScript, result -> {
                if (!"\"SUBMITTED\"".equals(result)) {
                    handleError("Web automation script failed: " + result);
                } else {
                    pollForResponse();
                }
            });
        }, 300); // 300ms 딜레이 추가
    }

    private void pollForResponse() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 40; // 20초 타임아웃 (0.5초 * 40)
        final int[] currentAttempt = {0};

        // --- 수정: 응답 생성 시작을 직접 감지하는 스크립트로 변경 ---
        final String checkGenerationStartScript = "(function() {" +
                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                "    const pendingRequest = document.querySelector('pending-request');" +
                "    return stopButton != null || pendingRequest != null;" +
                "})();";

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return;

                currentAttempt[0]++;
                if (currentAttempt[0] > maxAttempts) {
                    Log.e(TAG, "Polling timed out waiting for response generation to start.");
                    handleError("Response generation timed out.");
                    return;
                }

                serviceContext.evaluateJavascriptInBackground(checkGenerationStartScript, isGenerating -> {
                    if (pendingCallback == null) return;

                    if ("true".equals(isGenerating)) {
                        Log.d(TAG, "Response generation has started. Proceeding to wait for completion.");
                        waitForResponseCompletion();
                    } else {
                        Log.d(TAG, "Waiting for response generation to start... Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, 500L);
                    }
                });
            }
        });
    }

    private void waitForResponseCompletion() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 20; // 20초 타임아웃 (1초 * 20)
        final int[] currentAttempt = {0};
        final String checkInProgressScript = "(function() {" +
                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                "    const pendingRequest = document.querySelector('pending-request');" +
                "    return stopButton != null || pendingRequest != null;" +
                "})();";

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return;

                currentAttempt[0]++;
                if (currentAttempt[0] > maxAttempts) {
                    Log.e(TAG, "Timed out waiting for response generation to complete. Attempting to stop generation.");
                    // 수정: 타임아웃 시 생성 중지 및 입력창 초기화 스크립트 실행
                    final String stopAndClearScript = "(function() {" +
                            "    function findElement(selector, root = document.body) {" +
                            "        const element = root.querySelector(selector);" +
                            "        if (element) return element;" +
                            "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                            "        for (const shadowRoot of shadowRoots) {" +
                            "            const found = findElement(selector, shadowRoot);" +
                            "            if (found) return found;" +
                            "        }" +
                            "        return null;" +
                            "    }" +
                            "    const stopButton = findElement('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                            "    if (stopButton) {" +
                            "        stopButton.click();" +
                            "        setTimeout(() => {" +
                            "            const editorDiv = findElement('rich-textarea .ql-editor');" +
                            "            if (editorDiv) {" +
                            "                while (editorDiv.firstChild) { editorDiv.removeChild(editorDiv.firstChild); }" +
                            "                const pTag = document.createElement('p');" +
                            "                const brTag = document.createElement('br');" +
                            "                pTag.appendChild(brTag);" +
                            "                editorDiv.appendChild(pTag);" +
                            "            }" +
                            "        }, 500);" +
                            "    }" +
                            "})();";
                    serviceContext.evaluateJavascriptInBackground(stopAndClearScript, result -> {
                        // 중지 시도 후 타임아웃 에러를 보고합니다.
                        handleError("Response generation timed out.");
                    });
                    return;
                }

                serviceContext.evaluateJavascriptInBackground(checkInProgressScript, isGenerating -> {
                    if ("true".equals(isGenerating)) {
                        Log.d("GeminiWebHelper", "Still generating response. Waiting... (" + currentAttempt[0] + "/" + maxAttempts + ")");
                        handler.postDelayed(this, 500L);
                    } else {
                        Log.d("GeminiWebHelper", "Response generation complete. Extracting final response.");
                        extractFinalResponse();
                    }
                });
            }
        });
    }

    private void extractFinalResponse() {
        final String extractionScript = "(function() {" +
                "    function findElement(selector, root = document.body) {" +
                "        const element = root.querySelector(selector);" +
                "        if (element) return element;" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            const found = findElement(selector, shadowRoot);" +
                "            if (found) return found;" +
                "        }" +
                "        return null;" +
                "    }" +
                "    const conversations = document.querySelectorAll('div.conversation-container');" +
                "    if (conversations.length === 0) { return 'ERROR: No conversation containers found.'; }" +
                "    const lastConversation = conversations[conversations.length - 1];" +
                "    const responseElement = findElement('model-response .markdown', lastConversation);" + // 수정: Shadow DOM 탐색 함수 사용
                "    if (!responseElement) { return 'ERROR: Response markdown element not found in last conversation.'; }" +
                "    return responseElement.textContent;" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(extractionScript, response -> {
            if (response == null || response.contains("\"ERROR:")) {
                handleError("Failed to extract response: " + response);
            } else {
                String cleanedResponse = (response != null && !"null".equals(response))
                        ? response.substring(1, response.length() - 1).replace("\\n", "\n").replace("\\\"", "\"")
                        : "";
                handleSuccess(cleanedResponse);
            }
        });
    }

    private void handleSuccess(String response) {
        // --- 수정: 콜백을 안전하게 처리하고 상태를 리셋합니다. ---
        ModelResponseCallback callbackToNotify = this.pendingCallback;
        this.pendingCallback = null; // 콜백 중복 호출 방지를 위해 즉시 null로 설정

        if (callbackToNotify != null) {
            callbackToNotify.onSuccess(response);
        }
        cleanup();
    }

    private void handleError(String error) {
        // --- 수정: 콜백을 안전하게 처리하고 상태를 리셋합니다. ---
        ModelResponseCallback callbackToNotify = this.pendingCallback;
        this.pendingCallback = null; // 콜백 중복 호출 방지를 위해 즉시 null로 설정

        if (callbackToNotify != null) {
            callbackToNotify.onError(error);
        }
        cleanup();
    }

    private void cleanup() {
        // isAwaitingLoginCheck = false; // --- 제거 ---
//        // 리스너를 null로 설정하여 이 인스턴스가 더 이상 페이지 로드 이벤트를 받지 않도록 합니다.
//        if (serviceContext != null) {
//            // 리스너를 제거하는 대신, 다른 헬퍼가 리스너를 덮어쓸 수 있도록 그대로 둡니다.
//            serviceContext.setPageLoadListener(null);
//        }
        // --- 추가: 모든 작업 완료/실패 시 상태 플래그를 확실히 리셋합니다. ---
        isAwaitingSubmission.set(false);
        pendingCallback = null;
    }

    // --- 추가: GemmaManager와 동일한 격자 그리기 메서드 ---
    private Bitmap drawGridOnBitmap(Bitmap originalBitmap) {
        Bitmap mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas canvas = new android.graphics.Canvas(mutableBitmap);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(android.graphics.Color.RED);
        paint.setStrokeWidth(1);
        paint.setStyle(android.graphics.Paint.Style.STROKE);

        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        int step = 100;

        // Draw vertical lines
        for (int x = step; x < width; x += step) {
            canvas.drawLine(x, 0, x, height, paint);
        }

        // Draw horizontal lines
        for (int y = step; y < height; y += step) {
            canvas.drawLine(0, y, width, y, paint);
        }

        return mutableBitmap;
    }

    // --- 추가: 현재 WebView URL을 로깅하는 디버깅 함수 ---
    private void logCurrentWebViewUrl() {
        if (serviceContext != null) {
            // JavaScript를 실행하여 현재 페이지의 URL을 가져옵니다.
            serviceContext.evaluateJavascriptInBackground("(function() { return window.location.href; })();", url -> {
                // 콜백에서 받은 URL의 양쪽 끝에 있는 따옴표를 제거하고 로그를 출력합니다.
                Log.d(TAG, "[DEBUG] Current WebView URL: " + (url != null ? url.replace("\"", "") : "N/A"));
            });
        }
    }

    public interface WebHelperCallback {
        void onSuccess(String result);
        void onFailure(String error);
    }
}