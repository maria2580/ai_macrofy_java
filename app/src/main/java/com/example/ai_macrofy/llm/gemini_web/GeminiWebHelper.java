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

import org.json.JSONArray;
import org.json.JSONException;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeminiWebHelper {

    private static final String TAG = "GeminiWebHelper";
    private static final String GEMINI_URL = "https://gemini.google.com";

    private final MyForegroundService serviceContext;
    private ModelResponseCallback pendingCallback;
    private String finalPrompt;
    private Bitmap pendingBitmap;
    private final AtomicBoolean isRequestInProgress = new AtomicBoolean(false);

    // --- 추가: 대화 ID 추적을 위한 상태 변수 ---
    private final Set<String> processedConversationIds = new HashSet<>();
    private String newConversationId = null; // 새로 발견된 응답 컨테이너의 ID를 저장

    private boolean isRetrying=false;
    public GeminiWebHelper(MyForegroundService serviceContext) {
        this.serviceContext = serviceContext;
        // --- PageLoadListener 설정 로직 제거 ---
    }

    // --- 추가: 외부(GeminiWebManager)에서 대화 추적 상태를 리셋하기 위한 메서드 ---
    public void resetConversationTracking() {
        Log.d(TAG, "Resetting conversation tracking state.");
        this.processedConversationIds.clear();
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

    private void checkIsRequestInProgress(){
        if (!isRequestInProgress.get()){
            handleError("it is called when isRequestInProgress is false");
        }
    }

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
                        Log.d(TAG, "User is logged in. Proceeding to switch model.");
                        // --- 수정: submitPrompt를 직접 호출하는 대신, 모델 전환 로직을 먼저 수행합니다. ---
                        switchToProModelAndSubmit();
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

    private void switchToProModelAndSubmit() {
        // This script first checks if "2.5 Pro" is already selected.
        // If not, it clicks the model switcher button to open the selection menu.
        final String switchScript = "(function() {" +
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
                "    const modelSwitchButton = findElement('button.gds-mode-switch-button');" +
                "    if (!modelSwitchButton) return 'DROPDOWN_NOT_FOUND';" +
                "    if (modelSwitchButton.textContent && modelSwitchButton.textContent.trim().includes('2.5 Pro')) {" +
                "        return 'ALREADY_PRO';" +
                "    }" +
                "    modelSwitchButton.click();" +
                "    return 'DROPDOWN_CLICKED';" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(switchScript, result -> {
            Log.d(TAG, "Model switch check returned: " + result);
            if ("\"ALREADY_PRO\"".equals(result)) {
                Log.d(TAG, "Model is already 2.5 Pro. Submitting prompt.");
                submitPrompt(GeminiWebHelper.this.finalPrompt, GeminiWebHelper.this.pendingBitmap, GeminiWebHelper.this.pendingCallback);
            } else if ("\"DROPDOWN_CLICKED\"".equals(result)) {
                Log.d(TAG, "Model switcher dropdown clicked. Waiting to select Pro model.");
                // Wait for the menu to appear before trying to click the item.
                new Handler(Looper.getMainLooper()).postDelayed(this::clickProModelButton, 550);
            } else {
                // If the button wasn't found, maybe the UI changed.
                // It's safer to proceed with the request than to fail it.
                Log.w(TAG, "Could not find model switcher button. Proceeding with default model.");
                submitPrompt(GeminiWebHelper.this.finalPrompt, GeminiWebHelper.this.pendingBitmap, GeminiWebHelper.this.pendingCallback);
            }
        });
    }

    private void clickProModelButton() {
        // This script finds and clicks the "2.5 Pro" option in the menu.
        // The menu items are typically in the light DOM, so findElement is not strictly needed but safe to use.
        final String clickScript = "(function() {" +
                "    const menuItems = Array.from(document.querySelectorAll('button[mat-menu-item]'));" +
                "    for (const item of menuItems) {" +
                "        const descElement = item.querySelector('.mode-desc');" +
                "        if (descElement && descElement.textContent.trim().includes('2.5 Pro')) {" +
                "            item.click();" +
                "            return 'PRO_BUTTON_CLICKED';" +
                "        }" +
                "    }" +
                "    return 'PRO_BUTTON_NOT_FOUND';" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(clickScript, result -> {
            Log.d(TAG, "Click Pro button returned: " + result);
            if ("\"PRO_BUTTON_CLICKED\"".equals(result)) {
                Log.d(TAG, "Successfully clicked '2.5 Pro'. Waiting for model switch to complete.");
                // Give a moment for the switch to register before submitting.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    submitPrompt(GeminiWebHelper.this.finalPrompt, GeminiWebHelper.this.pendingBitmap, GeminiWebHelper.this.pendingCallback);
                }, 550);
            } else {
                // If the button wasn't found, fail the operation as this is an explicit goal.
                handleError("Failed to click '2.5 Pro' button in model switcher menu.");
            }
        });
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
        checkIsRequestInProgress();
        Log.d(TAG, "Submitting prompt with image.");
        Bitmap bitmapWithGrid = drawGridOnBitmap(pendingBitmap);
        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
        bitmapWithGrid.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        String base64Image = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP);
        if(base64Image==null){
            handleError("Failed to encode image to base64.");
        }
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
                    } else if ("\"NOT_FOUND\"".equals(result)) {
                        Log.d(TAG, "Submitting prompt with image. because of NOT_FOUND");
                        Bitmap bitmapWithGrid = drawGridOnBitmap(pendingBitmap);
                        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
                        bitmapWithGrid.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                        byte[] byteArray = byteArrayOutputStream.toByteArray();
                        String base64Image = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP);
                        if(base64Image==null){
                            handleError("Failed to encode image to base64.");
                        }
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

                        serviceContext.evaluateJavascriptInBackground(pasteScript, result2 -> {
                            String cleanedResult = result2 != null ? result2.replace("\"", "") : "";
                            if ("PASTE_EVENT_DISPATCHED".equals(cleanedResult)) {
                                Log.d(TAG, "Paste event dispatched successfully.");
                                return;
                            } else {
                                handleError("Failed to dispatch paste event: " + cleanedResult);
                            }
                        });
                        handler.postDelayed(this, 500L);


                    } else if (currentAttempt[0] < maxAttempts) {
                        checkIsRequestInProgress();
                        Log.d(TAG, "Waiting for image upload... Status: " + (result != null ? result : "null") + ", Attempt " + currentAttempt[0]);
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
            waitForResponseCompletion();
        });
    }

    private void waitForResponseCompletion() {
        Log.d(TAG, "Starting 2-stage response completion check based on <pending-request> tag.");
        waitForPendingRequestToAppear();
    }

    /**
     * Stage 1: Waits for the <pending-request> tag to appear on the page,
     * indicating that the model has started thinking.
     */
    private void waitForPendingRequestToAppear() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final String script = "(function() { return document.querySelector('pending-request') !== null; })();";
        final int maxAttempts = 30; // 3-second timeout
        final int[] currentAttempt = {0};

        Log.d(TAG, "[Stage 1] Waiting for <pending-request> to appear.");

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return;
                checkIsRequestInProgress();
                currentAttempt[0]++;
                serviceContext.evaluateJavascriptInBackground(script, result -> {
                    if (pendingCallback == null) return;

                    Log.d(TAG, "[Debug][Stage 1] <pending-request> appearance check returned: " + result + " (Attempt " + currentAttempt[0] + ")");

                    if ("true".equals(result)) {
                        Log.d(TAG, "[Stage 1] <pending-request> tag appeared. Proceeding to Stage 2.");
                        waitForPendingRequestToDisappear();
                    } else {
                        if (currentAttempt[0] < maxAttempts) {
                            handler.postDelayed(this, 100L);
                        } else {
                            waitForPendingRequestToDisappear();

                        }
                    }
                });
            }
        });
    }
    private void stopAndClearInput(){
        isRetrying=true;
        //중단 버튼을 누르는 로직을 수행한다(중단버튼을 못찾으면 다음 로직을 수행)
        String stopGenerationScript = "(function() {\n" +
                "    // 'aria-label'이 \"대답 생성 중지\" 또는 \"Stop generating response\"인 버튼을 찾습니다.\n" +
                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');\n" +
                "    \n" +
                "    // 버튼이 존재하면 클릭 이벤트를 실행합니다.\n" +
                "    if (stopButton) {\n" +
                "        stopButton.click();\n" +
                "    }\n" +
                "})();";
        serviceContext.evaluateJavascriptInBackground(escapeForJsString(stopGenerationScript), result -> {
            Log.d(TAG, "Stop button clicked.");
        });
        //텍스트를 지우는 로직을 수행한다.
        String clearInputScript = "(function() {" +
                "    /**" +
                "     * Finds an element in the DOM, searching through Shadow DOMs if necessary." +
                "     * @param {string} selector - The CSS selector for the element." +
                "     * @param {Document|ShadowRoot} root - The starting point for the search." +
                "     * @returns {HTMLElement|null} The found element or null." +
                "     */" +
                "    function findElement(selector, root = document.body) {" +
                "        const element = root.querySelector(selector);" +
                "        if (element) return element;" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*'))" +
                "            .map(el => el.shadowRoot)" +
                "            .filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            const found = findElement(selector, shadowRoot);" +
                "            if (found) return found;" +
                "        }" +
                "        return null;" +
                "    }" +
                "    const editorDiv = findElement('rich-textarea .ql-editor');" +
                "    if (!editorDiv) {" +
                "        console.error('ERROR: Editor div not found.');" +
                "        return;" +
                "    }" +
                "    let p = editorDiv.querySelector('p');" +
                "    if (!p) {" +
                "        p = document.createElement('p');" +
                "        editorDiv.appendChild(p);" +
                "    }" +
                "    while (p.firstChild) {" +
                "        p.removeChild(p.firstChild);" +
                "    }" +
                "})();";
        serviceContext.evaluateJavascriptInBackground(escapeForJsString(clearInputScript), result -> {
            Log.d(TAG, "Text cleared.");
        });
        //image는 지우지 않습니다.

        //이미지가 있었던 없었던 그대로 수행
        submitTextAndSend();
    }
    /**
     * Stage 2: Waits for the <pending-request> tag to disappear,
     * indicating that the model has finished generating the response structure.
     */
    private void waitForPendingRequestToDisappear() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final String script = "(function() { return document.querySelector('pending-request') === null; })();";
        final int maxAttempts = 60; // 30-second timeout
        final int[] currentAttempt = {0};

        Log.d(TAG, "[Stage 2] Waiting for <pending-request> to disappear.");

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return;
                checkIsRequestInProgress();

                currentAttempt[0]++;
                serviceContext.evaluateJavascriptInBackground(script, result -> {
                    if (pendingCallback == null) return;

                    Log.d(TAG, "[Debug][Stage 2] <pending-request> disappearance check returned: " + result + " (Attempt " + currentAttempt[0] + ")");

                    if ("true".equals(result)) {
                        Log.d(TAG, "[Stage 2] <pending-request> tag disappeared. Response generation is complete.");
                        pollForResponse();
                    } else {
                        if (currentAttempt[0] < maxAttempts) {
                            handler.postDelayed(this, 500L);
                        } else {
                            // Fallback based on previous user feedback: if it times out, assume it's done anyway.
                            Log.w(TAG, "[Stage 2] Timed out waiting for <pending-request> to disappear. Assuming completion and proceeding as a fallback.");
                            pollForResponse();
                        }
                    }
                });
            }
        });
    }

    private void pollForResponse() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 50; // 10 seconds timeout
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
                checkIsRequestInProgress();

                currentAttempt[0]++;
                if (currentAttempt[0] > maxAttempts) {
                    Log.e(TAG, "Timed out waiting for response generation to start.");
                    if (isRetrying) {
                        handleError("Timed out waiting for response generation to start. ");
                        return;
                    }
                    stopAndClearInput();
                    return;
                }

                serviceContext.evaluateJavascriptInBackground(checkGenerationStartScript, isGenerating -> {
                    if (pendingCallback == null) return;

                    if ("true".equals(isGenerating)) {
                        Log.d(TAG, "Response generation has started. Waiting for completion.");
                        pollForNewResponse();
                    } else {
                        Log.d(TAG, "Waiting for response generation to start... Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, 200L);
                    }
                });
            }
        };
        handler.post(pollRunnable);
    }

    // --- 추가: 새로운 응답 컨테이너를 찾는 폴링 메서드 ---
    private void pollForNewResponse() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] currentAttempt = {0};
        final int maxAttempts = 50; // 10 seconds

        // 페이지의 모든 대화 컨테이너 ID를 가져오는 스크립트
        final String getConversationIdsScript = "(function() {" +
                "  const containers = document.querySelectorAll('div.conversation-container');" +
                "  const ids = Array.from(containers).map(c => c.id);" +
                "  return JSON.stringify(ids);" +
                "})();";

        Log.d(TAG, "[Debug] Polling for new response. Known IDs: " + processedConversationIds.toString());

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return; // 작업 취소
                checkIsRequestInProgress();

                currentAttempt[0]++;

                serviceContext.evaluateJavascriptInBackground(getConversationIdsScript, allIdsJson -> {
                    if (pendingCallback == null) return;

                    Log.d(TAG, "[Debug] Conversation ID script returned: " + allIdsJson);

                    // JavaScript로부터 받은 문자열은 양 끝에 큰따옴표가 있을 수 있으므로 제거하고, 이스케이프된 따옴표를 복원합니다.
                    String cleanedJson = allIdsJson;
                    if (cleanedJson.length() > 1 && cleanedJson.startsWith("\"") && cleanedJson.endsWith("\"")) {
                        cleanedJson = cleanedJson.substring(1, cleanedJson.length() - 1);
                    }
                    cleanedJson = cleanedJson.replace("\\\"", "\"");

                    if ("[]".equals(cleanedJson)) {
                         if (currentAttempt[0] < maxAttempts) {
                            Log.d(TAG, "[Debug] No conversation containers found yet. Retrying... (Attempt " + currentAttempt[0] + ")");
                            handler.postDelayed(this, 200L);
                        } else {
                            handleError("Timed out waiting for a new response container to appear.");
                        }
                        return;
                    }

                    try {
                        JSONArray allIds = new JSONArray(cleanedJson); // 수정: 정리된 JSON 문자열 사용
                        String foundNewId = null;
                        // 모든 ID를 순회하며 이전에 처리되지 않은 ID를 찾음
                        for (int i = 0; i < allIds.length(); i++) {
                            String id = allIds.getString(i);
                            if (!processedConversationIds.contains(id)) {
                                foundNewId = id;
                                Log.d(TAG, "[Debug] New conversation ID detected. Old IDs: " + processedConversationIds.toString() + ", New ID: " + foundNewId);
                                Log.d(TAG, "Found new response container with ID: " + foundNewId);
                            }
                        }

                        if (foundNewId != null) {
                            Log.d(TAG, "Found new response container with ID: " + foundNewId);
                            newConversationId = foundNewId;
                            extractFinalResponse(newConversationId); // 찾은 ID로 응답 추출 시작
                        } else {
                            if (currentAttempt[0] < maxAttempts) {
                                Log.d(TAG, "[Debug] No new response container found yet. Retrying... Processed IDs: " + processedConversationIds.size() + ", Attempt " + currentAttempt[0]);
                                handler.postDelayed(this, 500L);
                            } else {
                                handleError("Timed out waiting for a new response container to appear.");
                            }
                        }
                    } catch (JSONException e) {
                        handleError("Failed to parse conversation IDs JSON: " + e.getMessage());
                    }
                });
            }
        });
    }
    private void pollForResponseFinal() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final int maxAttempts = 10; // 20 seconds timeout
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
                checkIsRequestInProgress();
                currentAttempt[0]++;
                if (currentAttempt[0] > maxAttempts) {
                    Log.e(TAG, "Timed out waiting for response generation to start.");
                    handleError("Response generation timed out.");
                    return;
                }

                serviceContext.evaluateJavascriptInBackground(checkGenerationStartScript, isGenerating -> {
                    if (pendingCallback == null) return;

                    if ("true".equals(isGenerating)) {
                        Log.d(TAG, "Waiting for response generation to end... Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, 200L);
                    } else {
                        return;
                    }
                });
            }
        };
        handler.post(pollRunnable);
    }
    // --- 수정: 특정 ID를 가진 컨테이너에서 응답을 추출하도록 변경 ---
    private void extractFinalResponse(String newConversationId) {
        this.newConversationId=null;
        if (newConversationId == null || newConversationId.isEmpty()) {
            handleError("Internal error: newConversationId is null or empty during extraction.");
            return;
        }
        pollForResponseFinal();
        final String extractionScript = "(function() {" +
                "    function findElement(selector, root) {" + // root is now mandatory
                "        if (!root) return null;" +
                "        const element = root.querySelector(selector);" +
                "        if (element) return element;" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            const found = findElement(selector, shadowRoot);" +
                "            if (found) return found;" +
                "        }" +
                "        return null;" +
                "    }" +
                "    const targetConversation = document.querySelector('div.conversation-container[id=\\\"" + newConversationId + "\\\"]');" +
                "    if (!targetConversation) { return 'ERROR: Target conversation container with id " + newConversationId + " not found.'; }" +
                "    const responseElement = findElement('model-response .markdown', targetConversation);" +
                "    if (!responseElement) { return 'ERROR: Response markdown element not found in target container.'; }" +
                "    return responseElement.textContent;" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(extractionScript, response -> {
            Log.d(TAG, "[Debug] Raw text extraction script returned: " + response);
            if (response == null || response.contains("\"ERROR:")) {
                handleError("Failed to extract response: " + response);
            } else {
                String cleanedResponse = (response != null && !"null".equals(response))
                        ? response.substring(1, response.length() - 1).replace("\\\\n", "\n").replace("\\\"", "\"").replace("\\n", "\n")
                        : "";

                // Per user request, immediately remove structural newlines from the raw response string.
                if (cleanedResponse != null) {
                    cleanedResponse = cleanedResponse.replace("\n", "").replace("\r", "");
                }

                // 성공적으로 처리된 ID를 Set에 추가
                processedConversationIds.add(newConversationId);
                Log.d(TAG, "Successfully processed and added ID to set: " + newConversationId + ". Set size: " + processedConversationIds.size());
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
        isRetrying=false;
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
        // processedConversationIds는 여기서 초기화하지 않음. 매니저가 세션 시작 시 리셋을 관리.
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