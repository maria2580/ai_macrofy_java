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

public class GeminiWebHelper implements MyForegroundService.PageLoadListener {

    private static final String TAG = "GeminiWebHelper";
    private static final String GEMINI_URL = "https://gemini.google.com";

    private final MyForegroundService serviceContext;
    private ModelResponseCallback pendingCallback;
    private String finalPrompt;
    private Bitmap pendingBitmap; // --- 추가: 비트맵 저장 ---

    private boolean isAwaitingLoginCheck = false;

    public GeminiWebHelper(MyForegroundService serviceContext) {
        this.serviceContext = serviceContext;
        // --- 추가: 생성자에서 PageLoadListener를 설정합니다. ---
        this.serviceContext.setPageLoadListener(this);
    }

    public void generateResponse(String finalPrompt, @Nullable Bitmap bitmap, ModelResponseCallback callback) {
        logCurrentWebViewUrl(); // --- 추가: 현재 URL 로깅 ---
        this.finalPrompt = finalPrompt;
        this.pendingBitmap = bitmap;
        this.pendingCallback = callback;
        this.isAwaitingLoginCheck = true;

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
                    WebView webView = SharedWebViewManager.getWebView();
                    // --- 수정: URL 확인 및 로딩 로직 단순화 ---
                    if (webView != null && webView.getUrl() != null && webView.getUrl().startsWith("https://gemini.google.com/app")) {
                        // 이미 올바른 페이지에 있다면, onPageFinished를 직접 호출하여 로직을 시작합니다.
                        Log.d(TAG, "Gemini app URL is already loaded. Triggering onPageFinished manually.");
                        onPageFinished(webView, webView.getUrl());
                    } else {
                        // 그렇지 않다면, URL을 로드하고 WebViewClient의 onPageFinished 콜백을 기다립니다.
                        Log.d(TAG, "Requesting to load Gemini URL. Waiting for onPageFinished callback from WebViewClient.");
                        serviceContext.loadUrlInBackground(GEMINI_URL);
                    }
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

    @Override
    public void onPageFinished(WebView view, String url) {
        if (!url.startsWith("https://gemini.google.com/app")) {
            Log.d(TAG, "onPageFinished for non-final URL, ignoring: " + url);
            return;
        }
        // isAwaitingLoginCheck 플래그를 사용하여 이 로직이 단 한 번만 실행되도록 보장합니다.
        if (!isAwaitingLoginCheck) {
            Log.d(TAG, "onPageFinished received, but not awaiting a login check. Ignoring.");
            return;
        }
        isAwaitingLoginCheck = false; // 플래그를 내려 중복 실행 방지
        Log.d(TAG, "onPageFinished received for the correct URL. Starting login check.");
        checkLoginStatus();
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
                if (pendingCallback == null) { // 콜백이 null이면 작업이 취소된 것이므로 중단
                    Log.d(TAG, "Login check stopped, callback is null.");
                    return;
                }
                currentAttempt[0]++;
                Log.d(TAG, "Checking for login... Attempt " + currentAttempt[0] + "/" + maxAttempts);
                serviceContext.evaluateJavascriptInBackground(checkLoginScript, loginStatus -> {
                    if (pendingCallback == null) return;

                    if ("\"LOGGED_IN\"".equals(loginStatus)) {
                        Log.d(TAG, "User is logged in. Proceeding to submit prompt.");
                        submitPrompt();
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

    private void submitPrompt() {
        if (pendingBitmap != null) {
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
                "    const maxAttempts = 25;" +
                "    const delay = 200;" +
                "    let attempts = 0;" +
                "    function performPaste() {" +
                "        const target = findElement('rich-textarea .ql-editor');" +
                "        if (target) {" +
                "            const base64Data = '" + base64Image + "';" +
                "            try {" +
                "                const byteCharacters = atob(base64Data);" +
                "                const byteNumbers = new Array(byteCharacters.length);" +
                "                for (let i = 0; i < byteCharacters.length; i++) {" +
                "                    byteNumbers[i] = byteCharacters.charCodeAt(i);" +
                "                }" +
                "                const byteArray = new Uint8Array(byteNumbers);" +
                "                const blob = new Blob([byteArray], {type: 'image/png'});" +
                "                const dataTransfer = new DataTransfer();" +
                "                dataTransfer.items.add(new File([blob], 'screenshot.png', {type: 'image/png'}));" +
                "                const pasteEvent = new ClipboardEvent('paste', { clipboardData: dataTransfer, bubbles: true, cancelable: true });" +
                "                target.dispatchEvent(pasteEvent);" +
                "                window.androidCallback('PASTE_EVENT_DISPATCHED');" +
                "            } catch (e) { window.androidCallback('ERROR: ' + e.message); }" +
                "        } else if (attempts < maxAttempts) {" +
                "            attempts++;" +
                "            setTimeout(performPaste, delay);" +
                "        } else {" +
                "            window.androidCallback('ERROR: Prompt input area (.ql-editor) not found after ' + (maxAttempts * delay) + 'ms.');" +
                "        }" +
                "    }" +
                "    performPaste();" +
                "})();";

            // 3. 스크립트 실행 (콜백을 통해 결과를 받도록 수정)
            serviceContext.evaluateJavascriptWithCallback(pasteScript, result -> {
                if ("PASTE_EVENT_DISPATCHED".equals(result)) {
                    Log.d(TAG, "Paste event dispatched successfully.");
                    // 4. 이미지 썸네일이 나타날 때까지 폴링
                    pollForImageAndSubmitPrompt();
                } else {
                    handleError("Failed to dispatch paste event: " + result);
                }
            });
        } else {
            // 이미지가 없는 경우
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
                "    const editorDiv = findElement('rich-textarea .ql-editor');" + // 수정: Shadow DOM 탐색 함수 사용
                "    if (!editorDiv) { return 'ERROR: Editor div not found.'; }" +
                "    let pTag = editorDiv.querySelector('p');" +
                "    if (!pTag) { pTag = document.createElement('p'); editorDiv.appendChild(pTag); }" +
                "    pTag.textContent = " + escapedPrompt + ";" +
                "    const sendButton = findElement('button[aria-label=\"Send message\"], button[aria-label=\"메시지 보내기\"]');" + // 수정: Shadow DOM 탐색 함수 사용
                "    if (!sendButton) { return 'ERROR: Send button not found.'; }" +
                "    setTimeout(() => { sendButton.click(); }, 100);" + // 짧은 딜레이 후 클릭
                "    return 'SUBMITTED';" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(automationScript, result -> {
            if (!"\"SUBMITTED\"".equals(result)) {
                handleError("Web automation script failed: " + result);
            } else {
                pollForResponse();
            }
        });
    }

    private void pollForResponse() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 40; // 20초 타임아웃 (0.5초 * 40)
        final int[] currentAttempt = {0};

        serviceContext.evaluateJavascriptInBackground("(function() { return document.querySelectorAll('div.conversation-container').length; })();", initialCountStr -> {
            int initialCount;
            try {
                initialCount = Integer.parseInt(initialCountStr);
            } catch (NumberFormatException e) {
                initialCount = 0;
            }
            Log.d(TAG, "Initial conversation count: " + initialCount);

            int finalInitialCount = initialCount;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (pendingCallback == null) return;

                    // --- 수정: 타임아웃 시 생성 중지 로직 추가 ---
                    currentAttempt[0]++;
                    if (currentAttempt[0] > maxAttempts) {
                        Log.e(TAG, "Polling timed out waiting for new conversation container. Attempting to stop generation.");
                        // 타임아웃 시 생성 중지 버튼을 클릭하여 멈춘 요청을 정리합니다.
                        final String stopGenerationScript = "(function() {" +
                                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                                "    if (stopButton) { stopButton.click(); }" +
                                "})();";
                        serviceContext.evaluateJavascriptInBackground(stopGenerationScript, result -> {
                            // 중지 시도 후 타임아웃 에러를 보고합니다.
                            handleError("Response timed out waiting for new conversation container.");
                        });
                        return;
                    }
                    // --- 로직 수정 끝 ---

                    String checkNewContainerScript = "(function() { return document.querySelectorAll('div.conversation-container').length > " + finalInitialCount + "; })();";
                    serviceContext.evaluateJavascriptInBackground(checkNewContainerScript, isNewContainer -> {
                        if (pendingCallback == null) return; // 콜백 실행 전 작업이 취소될 수 있음

                        if ("true".equals(isNewContainer)) {
                            Log.d(TAG, "New conversation container appeared. Proceeding to wait for completion.");
                            waitForResponseCompletion();
                        } else {
                            Log.d(TAG, "Waiting for new conversation container... Attempt " + currentAttempt[0]);
                            handler.postDelayed(this, 500L);
                        }
                    });
                }
            });
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
                    // 타임아웃 시 생성 중지 버튼을 클릭하여 멈춘 요청을 정리합니다.
                    final String stopGenerationScript = "(function() {" +
                            "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                            "    if (stopButton) { stopButton.click(); }" +
                            "})();";
                    serviceContext.evaluateJavascriptInBackground(stopGenerationScript, result -> {
                        // 중지 시도 후 타임아웃 에러를 보고합니다.
                        handleError("Response generation timed out.");
                    });
                    return;
                }

                serviceContext.evaluateJavascriptInBackground(checkInProgressScript, isGenerating -> {
                    if ("true".equals(isGenerating)) {
                        Log.d("GeminiWebHelper", "Still generating response. Waiting... (" + currentAttempt[0] + "/" + maxAttempts + ")");
                        handler.postDelayed(this, 1000L);
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
        if (pendingCallback != null) {
            pendingCallback.onSuccess(response);
            pendingCallback = null;
        }
        cleanup();
    }

    private void handleError(String error) {
        if (pendingCallback != null) {
            pendingCallback.onError(error);
            pendingCallback = null;
        }
        cleanup();
    }

    private void cleanup() {
        isAwaitingLoginCheck = false;
        // 리스너를 null로 설정하여 이 인스턴스가 더 이상 페이지 로드 이벤트를 받지 않도록 합니다.
        if (serviceContext != null) {
            // 리스너를 제거하는 대신, 다른 헬퍼가 리스너를 덮어쓸 수 있도록 그대로 둡니다.
            // serviceContext.setPageLoadListener(null);
        }
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