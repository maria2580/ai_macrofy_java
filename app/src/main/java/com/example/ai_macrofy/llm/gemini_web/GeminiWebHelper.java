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

import java.util.concurrent.atomic.AtomicBoolean;

public class GeminiWebHelper {

    private static final String TAG = "GeminiWebHelper";
    private static final String GEMINI_URL = "https://gemini.google.com";

    private final MyForegroundService serviceContext;
    private ModelResponseCallback pendingCallback;
    private String finalPrompt;
    private Bitmap pendingBitmap;
    private final AtomicBoolean isRequestInProgress = new AtomicBoolean(false);

    public GeminiWebHelper(MyForegroundService serviceContext) {
        this.serviceContext = serviceContext;
        // --- PageLoadListener 설정 로직 제거 ---
    }

    public void generateResponse(String finalPrompt, @Nullable Bitmap bitmap, ModelResponseCallback callback) {
        logCurrentWebViewUrl();
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
                if (SharedWebViewManager.isReady()) {
                    Log.d(TAG, "WebView is ready. Proceeding to load URL and check login.");
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

        Runnable checkRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null && !isRequestInProgress.get()) {
                    Log.d(TAG, "Login check stopped, callback is null and not in progress.");
                    return;
                }
                currentAttempt[0]++;
                Log.d(TAG, "Checking for login... Attempt " + currentAttempt[0] + "/" + maxAttempts);
                serviceContext.evaluateJavascriptInBackground(checkLoginScript, loginStatus -> {
                    if (pendingCallback == null && !isRequestInProgress.get()) return;

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
        };
        handler.post(checkRunnable);
    }

    public void submitPrompt(String finalPrompt, @Nullable Bitmap bitmap, ModelResponseCallback callback) {
        if (!isRequestInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Submission in progress. Ignoring new request.");
            if (callback != null) {
                // Immediately notify the new caller that its request was ignored.
                new Handler(Looper.getMainLooper()).post(() -> callback.onError("Another request is already in progress. Please try again."));
            }
            return;
        }

        Log.d(TAG, "Request lock acquired. Setting new callback and proceeding.");
        this.finalPrompt = finalPrompt;
        this.pendingBitmap = bitmap;
        this.pendingCallback = callback; // --- 수정: submitPrompt에서도 콜백을 설정하도록 복원 ---

        if (pendingBitmap != null) {
            submitImageAndPrompt();
        } else {
            submitTextAndSend();
        }
    }

    private void submitImageAndPrompt() {
        Log.d(TAG, "Submitting prompt with image.");
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
            "    if (!target) { return 'ERROR: Prompt input area not found.'; }" +
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
            "        return 'PASTE_EVENT_DISPATCHED';" +
            "    } catch (e) { return 'ERROR: ' + e.message; }" +
            "})();";

        serviceContext.evaluateJavascriptInBackground(pasteScript, result -> {
            String cleanedResult = result != null ? result.replace("\"", "") : "";
            if ("PASTE_EVENT_DISPATCHED".equals(cleanedResult)) {
                Log.d(TAG, "Paste event dispatched successfully.");
                pollForImageAndSubmitPrompt();
            } else {
                handleError("Failed to dispatch paste event: " + cleanedResult);
            }
        });
    }

    private void pollForImageAndSubmitPrompt() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 25; // 12.5 seconds timeout
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

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return; // 작업 취소됨
                currentAttempt[0]++;
                serviceContext.evaluateJavascriptInBackground(checkImageScript, result -> {
                    // --- 수정: 3가지 상태(COMPLETED, UPLOADING, NOT_FOUND)를 처리하도록 로직 개선 ---
                    if ("\"COMPLETED\"".equals(result)) {
                        Log.d(TAG, "Image upload completed. Proceeding to submit prompt text.");
                        submitTextAndSend();
                    } else if (currentAttempt[0] < maxAttempts) {
                        Log.d(TAG, "Waiting for image upload... Status: " + (result != null ? result : "null") + ", Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, 500L);
                    } else {
                        handleError("Timed out waiting for image upload to complete. Final status: " + result);
                    }
                });
            }
        };
        handler.post(pollRunnable);
    }

    private void submitTextAndSend() {
        String escapedPrompt = escapeForJsString(finalPrompt);
        String finalAutomationScript =
                "(function() {" +
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
                // First, check if a response is already generating and stop it.
                "    const stopButton = findElement('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                "    if (stopButton && !stopButton.disabled) {" +
                "        stopButton.click();" +
                "        return;" +
                "    }" +
                // If not generating, proceed with submission.
                "    const editorDiv = findElement('rich-textarea .ql-editor');" +
                "    if (!editorDiv) { return; }" +
                "    let p = editorDiv.querySelector('p');" +
                "    if (!p) { p = document.createElement('p'); editorDiv.appendChild(p); }" +
                "    while (p.firstChild) { p.removeChild(p.firstChild); }" +
                "    p.appendChild(document.createTextNode(" + escapedPrompt + "));" +
                // Wait a moment for the send button to become enabled after text is inserted.
                "    setTimeout(function() {" +
                "        const sendButton = findElement('button[aria-label=\"Send message\"]:not([disabled]), button[aria-label=\"메시지 보내기\"]:not([disabled])');" +
                "        if (sendButton) {" +
                "            sendButton.click();" +
                "        }" +
                "    }, 100);" +
                "})();";

        // We execute the script and optimistically assume it worked.
        // Then we immediately start polling for the response generation state.
        serviceContext.evaluateJavascriptInBackground(finalAutomationScript, result -> {
            Log.d(TAG, "Submission script executed. Starting to poll for response.");
            pollForResponse();
        });
    }

    private void pollForResponse() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 40; // 20 seconds timeout
        final int[] currentAttempt = {0};

        // --- 수정: 응답 생성 시작을 직접 감지하는 스크립트로 변경 ---
        final String checkGenerationStartScript = "(function() {" +
                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                "    const pendingRequest = document.querySelector('pending-request');" +
                "    return stopButton != null || pendingRequest != null;" +
                "})();";

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return;

                currentAttempt[0]++;
                if (currentAttempt[0] > maxAttempts) {
                    Log.e(TAG, "Timed out waiting for response generation to start.");
                    handleError("Response generation timed out.");
                    return;
                }

                serviceContext.evaluateJavascriptInBackground(checkGenerationStartScript, isGenerating -> {
                    if (pendingCallback == null) return;

                    if ("true".equals(isGenerating)) {
                        Log.d(TAG, "Response generation has started. Waiting for completion.");
                        waitForResponseCompletion();
                    } else {
                        Log.d(TAG, "Waiting for response generation to start... Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, 500L);
                    }
                });
            }
        };
        handler.post(pollRunnable);
    }

    private void waitForResponseCompletion() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 30; // 15 seconds timeout
        final int[] currentAttempt = {0};
        final String checkInProgressScript = "(function() {" +
                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                "    const pendingRequest = document.querySelector('pending-request');" +
                "    return stopButton != null || pendingRequest != null;" +
                "})();";

        Runnable checkRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return;

                currentAttempt[0]++;
                if (currentAttempt[0] > maxAttempts) {
                    Log.e(TAG, "Timed out waiting for response to complete.");
                    handleError("Response generation timed out.");
                    return;
                }

                serviceContext.evaluateJavascriptInBackground(checkInProgressScript, isGenerating -> {
                    if ("true".equals(isGenerating)) {
                        Log.d(TAG, "Still generating response... (" + currentAttempt[0] + "/" + maxAttempts + ")");
                        handler.postDelayed(this, 500L);
                    } else {
                        Log.d(TAG, "Response generation complete. Extracting final response.");
                        extractFinalResponse();
                    }
                });
            }
        };
        handler.post(checkRunnable);
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
                "    const responseElement = findElement('model-response .markdown', lastConversation);" +
                "    if (!responseElement) { return 'ERROR: Response markdown element not found.'; }" +
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

    private String escapeForJsString(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\"");
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 32 || c > 126) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private void handleSuccess(String response) {
        // --- 수정: 콜백을 안전하게 처리하고 상태를 리셋합니다. ---
        ModelResponseCallback callbackToNotify = this.pendingCallback;
        if (callbackToNotify != null) {
            new Handler(Looper.getMainLooper()).post(() -> callbackToNotify.onSuccess(response));
        }
        cleanup();
    }

    private void handleError(String error) {
        // --- 수정: 콜백을 안전하게 처리하고 상태를 리셋합니다. ---
        ModelResponseCallback callbackToNotify = this.pendingCallback;
        Log.e(TAG, "Error: " + error);
        if (callbackToNotify != null) {
            new Handler(Looper.getMainLooper()).post(() -> callbackToNotify.onError(error));
        }
        cleanup();
    }

    private void cleanup() {
        Log.d(TAG, "Cleaning up and releasing request lock.");
        this.pendingCallback = null;
        this.finalPrompt = null;
        this.pendingBitmap = null;
        this.isRequestInProgress.set(false);
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
}