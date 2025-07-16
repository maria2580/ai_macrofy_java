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
    private final Handler handler; // --- 추가: 핸들러 멤버 변수 ---
    private ModelResponseCallback pendingCallback;
    private String finalPrompt;
    private Bitmap pendingBitmap;
    private final AtomicBoolean isRequestInProgress = new AtomicBoolean(false);

    // --- 추가: 대화 ID 추적을 위한 상태 변수 ---
    private final Set<String> processedConversationIds = new HashSet<>();
    private String newConversationId = null; // 새로 발견된 응답 컨테이너의 ID를 저장

    private boolean isRetrying=false;

    private boolean isTaskCancelled() {
        if (pendingCallback == null) {
            return true;
        }
        if (!SharedWebViewManager.isReady()) {
            Log.w(TAG, "Task cancelled because WebView is not ready. Cleaning up...");
            cleanup();
            return true;
        }
        return false;
    }

    public GeminiWebHelper(MyForegroundService serviceContext) {
        this.serviceContext = serviceContext;
        this.handler = new Handler(Looper.getMainLooper()); // --- 추가: 핸들러 초기화 ---
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

        final int maxAttempts = 5;
        final int[] currentAttempt = {0};

        Runnable checkRunnable = new Runnable() {
            @Override
            public void run() {
                if (isTaskCancelled()) {
                    Log.d(TAG, "Login check stopped, task was cancelled.");
                    return;
                }
                currentAttempt[0]++;
                Log.d(TAG, "Checking for login... Attempt " + currentAttempt[0] + "/" + maxAttempts);
                serviceContext.evaluateJavascriptInBackground(checkLoginScript, loginStatus -> {
                    if (isTaskCancelled()) return;

                    if ("\"LOGGED_IN\"".equals(loginStatus)) {
                        Log.d(TAG, "User is logged in. Waiting for page to be ready before proceeding.");
                        waitForPageReadyAndProceed();
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

    private void waitForPageReadyAndProceed() {
        final int maxAttempts = 50; // 10 seconds
        final int[] currentAttempt = {0};

        // --- 수정: 페이지 상태를 더 자세히 진단하는 스크립트로 변경 ---
        final String checkScript = "(function() {" +
                "    function findElement(selector, root = document.body) {" +
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
                "    if (findElement('rich-textarea .ql-editor')) return 'READY_PROMPT_AREA';" +
                "    if (findElement('button[aria-label*=\"Send\"], button[aria-label*=\"보내기\"]')) return 'READY_SEND_BUTTON';" +
                "    if (findElement('textarea[aria-label*=\"Prompt\"]')) return 'READY_TEXTAREA';" +
                "    if (findElement('.welcome-mat, .intro-popup, [role=\"dialog\"]')) return 'BLOCKED_BY_MODAL';" +
                "    return 'WAITING';" +
                "})();";

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isTaskCancelled()) return;
                currentAttempt[0]++;

                serviceContext.evaluateJavascriptInBackground(checkScript, result -> {
                    if (isTaskCancelled()) return;

                    String status = "UNKNOWN";
                    if (result != null && !"null".equals(result)) {
                        status = result.replace("\"", "");
                    }

                    if (status.startsWith("READY")) {
                        Log.d(TAG, "Page is ready (" + status + "). Proceeding to switch model.");
                        switchToProModelAndSubmit();
                    } else if ("BLOCKED_BY_MODAL".equals(status)) {
                        Log.d(TAG, "Page is blocked by a modal. Attempting to close it... Attempt " + currentAttempt[0]);

                        final String closeModalScript = "(function() {" +
                                "    function findAndClick(root) {" +
                                "        const selectors = [" +
                                "            'button[aria-label*=\"close\" i]', 'button[aria-label*=\"닫기\"]'," +
                                "            'button[aria-label*=\"dismiss\" i]', 'button[aria-label*=\"got it\" i]', 'button[aria-label*=\"알겠습니다\"]'" +
                                "        ];" +
                                "        for (const selector of selectors) {" +
                                "            const el = root.querySelector(selector);" +
                                "            if (el) { el.click(); return true; }" +
                                "        }" +
                                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                                "        for (const shadowRoot of shadowRoots) {" +
                                "            if (findAndClick(shadowRoot)) return true;" +
                                "        }" +
                                "        return false;" +
                                "    }" +
                                "    findAndClick(document.body);" +
                                "})();";
                        serviceContext.evaluateJavascriptInBackground(closeModalScript, r -> {});

                        if (currentAttempt[0] < maxAttempts) {
                            handler.postDelayed(this, 500L); // Wait a bit for modal to disappear
                        } else {
                            handleError("Timed out trying to close modal. Final status: " + status);
                        }
                    } else { // WAITING or other states
                        Log.d(TAG, "Checking if page is fully loaded... Status: " + status + ", Attempt " + currentAttempt[0]);
                        if (currentAttempt[0] < maxAttempts) {
                            handler.postDelayed(this, 200L);
                        } else {
                            handleError("Timed out waiting for page to become ready. Final status: " + status);
                        }
                    }
                });
            }
        };
        handler.post(pollRunnable);
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
            if (isTaskCancelled()) return;
            Log.d(TAG, "Model switch check returned: " + result);
            if ("\"ALREADY_PRO\"".equals(result)) {
                Log.d(TAG, "Model is already 2.5 Pro. Submitting prompt.");
                submitPrompt(GeminiWebHelper.this.finalPrompt, GeminiWebHelper.this.pendingBitmap, GeminiWebHelper.this.pendingCallback);
            } else if ("\"DROPDOWN_CLICKED\"".equals(result)) {
                Log.d(TAG, "Model switcher dropdown clicked. Waiting to select Pro model.");
                // Wait for the menu to appear before trying to click the item.
                handler.postDelayed(this::clickProModelButton, 550);
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
        // It now includes a helper to search inside Shadow DOMs.
        final String clickScript = "(function() {" +
                "    function findAllElements(selector, root = document.body) {" +
                "        let allElements = Array.from(root.querySelectorAll(selector));" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) {" +
                "            allElements = allElements.concat(findAllElements(selector, shadowRoot));" +
                "        }" +
                "        return allElements;" +
                "    }" +
                "    const menuItems = findAllElements('button[mat-menu-item]');" +
                "    for (const item of menuItems) {" +
                "        if (item.textContent && item.textContent.trim().includes('2.5 Pro')) {" +
                "            item.click();" +
                "            return 'PRO_BUTTON_CLICKED';" +
                "        }" +
                "    }" +
                "    return 'PRO_BUTTON_NOT_FOUND';" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(clickScript, result -> {
            if (isTaskCancelled()) return;
            Log.d(TAG, "Click Pro button returned: " + result);
            if ("\"PRO_BUTTON_CLICKED\"".equals(result)) {
                Log.d(TAG, "Successfully clicked '2.5 Pro'. Waiting for model switch to complete.");
                // Give a moment for the switch to register before submitting.
                handler.postDelayed(() -> {
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
                handler.post(() -> callback.onError("Another request is already in progress. Please try again."));
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
            if (isTaskCancelled()) return; // 작업 취소됨

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
        final int maxAttempts = 25; // 12.5 seconds timeout
        final int[] currentAttempt = {0};

        // --- 수정: Shadow DOM 및 다양한 UI 상태를 고려하여 이미지 업로드 확인 스크립트를 더욱 견고하게 변경 ---
        final String checkImageScript = "(function() {" +
                "    function findElement(selector, root = document.body) {" +
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
                "    const editor = findElement('rich-textarea .ql-editor');" +
                "    if (editor) {" +
                "        const imgInEditor = findElement('img[src^=\"blob:\"], img[src^=\"data:\"]', editor);" +
                "        if (imgInEditor) return 'COMPLETED';" +
                "    }" +
                "    if (findElement('file-chip')) return 'COMPLETED';" +
                "    const uploaderPreview = findElement('uploader-file-preview');" +
                "    if (uploaderPreview) {" +
                "        const isLoading = findElement('.loading', uploaderPreview.shadowRoot || uploaderPreview);" +
                "        return isLoading ? 'UPLOADING' : 'COMPLETED';" +
                "    }" +
                "    if (findElement('mat-progress-spinner, [role=\"progressbar\"]')) return 'UPLOADING';" +
                "    return 'WAITING';" +
                "})();";

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isTaskCancelled()) return; // 작업 취소됨
                currentAttempt[0]++;
                serviceContext.evaluateJavascriptInBackground(checkImageScript, result -> {
                    if (isTaskCancelled()) return; // 작업 취소됨

                    String status = "UNKNOWN";
                    if (result != null && !"null".equals(result)) {
                        status = result.replace("\"", "");
                    }

                    if ("COMPLETED".equals(status)) {
                        Log.d(TAG, "Image upload completed. Proceeding to submit prompt text.");
                        submitTextAndSend();
                    } else if (currentAttempt[0] < maxAttempts) {
                        checkIsRequestInProgress();
                        Log.d(TAG, "Waiting for image upload... Status: " + status + ", Attempt " + currentAttempt[0]);
                        handler.postDelayed(this, 500L); // WAITING, UPLOADING 등 다른 상태일 때 계속 폴링
                    } else {
                        handleError("Timed out waiting for image upload to complete. Final status: " + status);
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
            if (isTaskCancelled()) return; // 작업 취소됨

            Log.d(TAG, "Submission script executed. Starting to poll for response.");
            pollForCompletion();
        });
    }

    private void pollForCompletion() {
        final int maxAttempts = 300; // 60-second timeout
        final int[] currentAttempt = {0};
        final boolean[] wasGenerating = {false};
        final String[] foundId = {null};

        // --- 수정: 응답 컨테이너 ID와 생성 상태를 동시에 확인하여, 생성 완료 후 ID를 사용하도록 로직 변경 ---
        final String pollScript = "(function() {" +
                "    let newId = null;" +
                "    const ids = [];" +
                "    function findConversations(root) {" +
                "        const containers = root.querySelectorAll('div.conversation-container');" +
                "        for (const c of containers) { if (c.id) { ids.push(c.id); } }" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) { findConversations(shadowRoot); }" +
                "    }" +
                "    findConversations(document.body);" +
                "    const knownIds = new Set(" + new JSONArray(processedConversationIds).toString() + ");" +
                "    for (const id of ids) {" +
                "        if (!knownIds.has(id)) {" +
                "            newId = id;" +
                "            break;" +
                "        }" +
                "    }" +
                "    let status = 'IDLE';" +
                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                "    const pendingRequest = document.querySelector('pending-request');" +
                "    if (stopButton || pendingRequest) {" +
                "        status = 'GENERATING';" +
                "    }" +
                "    return JSON.stringify({status: status, id: newId});" +
                "})();";

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isTaskCancelled()) return;
                currentAttempt[0]++;

                serviceContext.evaluateJavascriptInBackground(pollScript, result -> {
                    if (isTaskCancelled()) return;

                    try {
                        String cleanedResult = result;
                        if (cleanedResult != null && cleanedResult.startsWith("\"") && cleanedResult.endsWith("\"")) {
                            cleanedResult = cleanedResult.substring(1, cleanedResult.length() - 1).replace("\\\"", "\"");
                        }

                        if (cleanedResult == null || cleanedResult.isEmpty() || "null".equals(cleanedResult)) {
                            if (currentAttempt[0] < maxAttempts) {
                                handler.postDelayed(this, 200L);
                            } else {
                                handleError("Polling script returned empty result after timeout.");
                            }
                            return;
                        }

                        org.json.JSONObject statusResult = new org.json.JSONObject(cleanedResult);
                        String status = statusResult.getString("status");
                        String id = statusResult.optString("id", null);

                        if (id != null && foundId[0] == null) {
                            foundId[0] = id;
                            Log.d(TAG, "Found new response container with ID: " + id);
                        }

                        Log.d(TAG, "[PollForCompletion] Status: " + status + " (Attempt " + currentAttempt[0] + ")");

                        if ("GENERATING".equals(status)) {
                            wasGenerating[0] = true;
                            if (currentAttempt[0] < maxAttempts) {
                                handler.postDelayed(this, 200L);
                            } else {
                                handleError("Timed out while response was still generating.");
                            }
                        } else if ("IDLE".equals(status)) {
                            if (wasGenerating[0]) {
                                // Success condition: generation has finished.
                                if (foundId[0] != null) {
                                    Log.d(TAG, "Generation finished. Extracting final response from container: " + foundId[0]);
                                    extractFinalResponse(foundId[0]);
                                } else {
                                    // --- 수정: ID를 찾지 못했을 경우, 여기서 다시 한번 최신 ID를 찾는 로직 추가 ---
                                    Log.w(TAG, "Generation finished, but no response container was found during generation. Attempting one last search.");
                                    findNewestContainerAndExtract();
                                }
                            } else {
                                // Still idle, haven't started generating yet.
                                if (currentAttempt[0] < maxAttempts) {
                                    handler.postDelayed(this, 200L);
                                } else {
                                    handleError("Timed out waiting for generation to start.");
                                }
                            }
                        } else {
                            // Should not happen with the new script
                            if (currentAttempt[0] < maxAttempts) {
                                handler.postDelayed(this, 200L);
                            } else {
                                handleError("Polling ended in an unexpected state: " + status);
                            }
                        }
                    } catch (org.json.JSONException e) {
                        if (currentAttempt[0] < maxAttempts) {
                            handler.postDelayed(this, 200L);
                        } else {
                            handleError("Failed to parse response poll status: " + e.getMessage() + " | Raw result: " + result);
                        }
                    }
                });
            }
        };
        handler.post(pollRunnable);
    }

    // --- 추가: 생성이 끝난 직후, 가장 최신의 응답 컨테이너를 찾아 추출을 시작하는 메서드 ---
    private void findNewestContainerAndExtract() {
        final String findScript = "(function() {" +
                "    const ids = [];" +
                "    function findConversations(root) {" +
                "        const containers = root.querySelectorAll('div.conversation-container');" +
                "        for (const c of containers) { if (c.id) { ids.push(c.id); } }" +
                "        const shadowRoots = Array.from(root.querySelectorAll('*')).map(el => el.shadowRoot).filter(Boolean);" +
                "        for (const shadowRoot of shadowRoots) { findConversations(shadowRoot); }" +
                "    }" +
                "    findConversations(document.body);" +
                "    const knownIds = new Set(" + new JSONArray(processedConversationIds).toString() + ");" +
                "    for (let i = ids.length - 1; i >= 0; i--) {" +
                "        if (!knownIds.has(ids[i])) {" +
                "            return ids[i];" +
                "        }" +
                "    }" +
                "    return null;" +
                "})();";

        serviceContext.evaluateJavascriptInBackground(findScript, newId -> {
            if (isTaskCancelled()) return;

            String cleanedId = "null".equals(newId) ? null : newId.replace("\"", "");

            if (cleanedId != null && !cleanedId.isEmpty()) {
                Log.d(TAG, "Found newest container with ID: " + cleanedId);
                extractFinalResponse(cleanedId);
            } else {
                handleError("Could not find any new response container after generation finished.");
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
    // --- 수정: 생성 완료 후 최종 텍스트를 추출하는 역할에 집중하도록 로직 변경 ---
    private void extractFinalResponse(String newConversationId) {
        if (newConversationId == null || newConversationId.isEmpty()) {
            handleError("Internal error: newConversationId is null or empty during extraction.");
            return;
        }

        final int maxAttempts = 60; // 15-second timeout
        final int[] currentAttempt = {0};

        // --- 수정: JSON/코드 블록을 우선적으로 탐색하는 더 정교한 추출 스크립트 ---
        final String extractionScript = "(function() {" +
                "    function findElement(selector, root) {" +
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
                "    if (!targetConversation) return 'ERROR: Container not found';" +
                "    const modelResponse = findElement('model-response', targetConversation);" +
                "    if (!modelResponse) return 'WAITING_FOR_CONTENT';" +
                // Priority 1: Look for code blocks which are likely to contain JSON
                "    const codeBlock = findElement('.code-block, pre, code', modelResponse);" +
                "    if (codeBlock && codeBlock.textContent) {" +
                "        let text = codeBlock.textContent.trim();" +
                "        if (text.startsWith('JSON')) { text = text.substring(4).trim(); }" +
                "        if (text) return text;" +
                "    }" +
                // Priority 2: Look for the general markdown container
                "    const markdownContent = findElement('.markdown', modelResponse);" +
                "    if (markdownContent && markdownContent.textContent && markdownContent.textContent.trim()) {" +
                "        return markdownContent.textContent;" +
                "    }" +
                // Priority 3: Fallback to the entire model-response text content
                "    if (modelResponse.textContent && modelResponse.textContent.trim()) {" +
                "        return modelResponse.textContent;" +
                "    }" +
                "    return 'WAITING_FOR_CONTENT';" +
                "})();";

        Runnable extractionPoller = new Runnable() {
            private String lastSeenText = "";
            private int stabilityCounter = 0;
            private final int STABILITY_THRESHOLD = 4; // 4 * 250ms = 1 second of stability
            private final String[] PLACEHOLDER_STRINGS = {"생각하는 과정 표시", "Show thinking", "script"};

            @Override
            public void run() {
                if (isTaskCancelled()) return;
                currentAttempt[0]++;

                serviceContext.evaluateJavascriptInBackground(extractionScript, response -> {
                    if (isTaskCancelled()) return;

                    Log.d(TAG, "[Extraction] Raw: " + response + " (Attempt " + currentAttempt[0] + ")");

                    String currentText = "WAITING_FOR_CONTENT";
                    if (response != null && !"null".equals(response) && !response.isEmpty()) {
                        String parsedResponse = response.startsWith("\"") ? response.substring(1, response.length() - 1) : response;
                        currentText = parsedResponse.replace("\\\\n", "\n").replace("\\\"", "\"").replace("\\n", "\n");
                    }

                    if (currentText.startsWith("ERROR:")) {
                        handleError("Failed to extract response: " + currentText);
                        return;
                    }

                    boolean isPlaceholder = false;
                    String trimmedLowerText = currentText.trim().toLowerCase();
                    for (String placeholder : PLACEHOLDER_STRINGS) {
                        if (trimmedLowerText.startsWith(placeholder.toLowerCase())) {
                            isPlaceholder = true;
                            break;
                        }
                    }

                    if ("WAITING_FOR_CONTENT".equals(currentText) || isPlaceholder) {
                        stabilityCounter = 0;
                        // Don't update lastSeenText, so we don't return a placeholder on timeout
                        Log.d(TAG, "[Extraction] Waiting for actual content (placeholder or empty).");
                    } else if (!currentText.equals(lastSeenText)) {
                        Log.d(TAG, "[Extraction] Content updated. Resetting stability counter.");
                        lastSeenText = currentText;
                        stabilityCounter = 0;
                    } else {
                        stabilityCounter++;
                        Log.d(TAG, "[Extraction] Content stable. Stability count: " + stabilityCounter);
                    }

                    if (stabilityCounter >= STABILITY_THRESHOLD) {
                        Log.d(TAG, "Response is stable. Finalizing extraction.");
                        // The old deduplication logic is kept as a safeguard
                        if (lastSeenText.length() > 0 && lastSeenText.length() % 2 == 0) {
                            int half = lastSeenText.length() / 2;
                            if (lastSeenText.substring(0, half).equals(lastSeenText.substring(half))) {
                                Log.d(TAG, "Deduplicating response: '" + lastSeenText + "' -> '" + lastSeenText.substring(0, half) + "'");
                                lastSeenText = lastSeenText.substring(0, half);
                            }
                        }
                        processedConversationIds.add(newConversationId);
                        handleSuccess(lastSeenText);
                    } else {
                        if (currentAttempt[0] < maxAttempts) {
                            handler.postDelayed(this, 250L);
                        } else {
                            if (lastSeenText != null && !lastSeenText.isEmpty()) {
                                Log.w(TAG, "Timed out waiting for response to stabilize, returning last seen content.");
                                handleSuccess(lastSeenText);
                            } else {
                                handleError("Timed out waiting for text content to appear in final container: " + newConversationId);
                            }
                        }
                    }
                });
            }
        };
        handler.post(extractionPoller);
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
            handler.post(() -> callbackToNotify.onSuccess(response));
        }
        cleanup();
    }

    private void handleError(String error) {
        // --- 수정: 콜백을 안전하게 처리하고 상태를 리셋합니다. ---
        isRetrying=false;
        ModelResponseCallback callbackToNotify = this.pendingCallback;
        Log.e(TAG, "Error: " + error);
        if (callbackToNotify != null) {
            handler.post(() -> callbackToNotify.onError(error));
        }
        cleanup();
    }

    public void cleanup() {
        Log.d(TAG, "Cleaning up and releasing request lock.");
        //handler.removeCallbacksAndMessages(null); // --- 추가: 모든 예약된 작업 취소 ---
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