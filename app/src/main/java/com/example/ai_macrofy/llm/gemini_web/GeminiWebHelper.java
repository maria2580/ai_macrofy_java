package com.example.ai_macrofy.llm.gemini_web;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.services.foreground.MyForegroundService;
import com.example.ai_macrofy.utils.SharedWebViewManager;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

public class GeminiWebHelper implements MyForegroundService.PageLoadListener {

    private static final String TAG = "GeminiWebHelper";
    private static final String GEMINI_URL = "https://gemini.google.com";

    private final MyForegroundService serviceContext;
    private ModelResponseCallback pendingCallback;
    private String finalPrompt;

    private final AtomicBoolean isOperationInProgress = new AtomicBoolean(false);
    private boolean isAwaitingLoginCheck = false;

    public GeminiWebHelper(MyForegroundService serviceContext) {
        this.serviceContext = serviceContext;
    }

    public void generateResponse(String finalPrompt, ModelResponseCallback callback) {
        this.finalPrompt = finalPrompt;
        this.pendingCallback = callback;
        this.isAwaitingLoginCheck = true;
        this.serviceContext.setPageLoadListener(this); // 리스너로 자신을 등록

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
                if (serviceContext.isWebViewReady()) {
                    Log.d(TAG, "WebView is ready. Proceeding to load URL.");
                    WebView webView = SharedWebViewManager.getWebView();
                    if (webView != null && webView.getUrl() != null && webView.getUrl().startsWith("https://gemini.google.com/app")) {
                        onPageFinished(webView, webView.getUrl());
                    } else {
                        serviceContext.loadUrlInBackground(GEMINI_URL);
                    }
                } else {
                    currentAttempt[0]++;
                    if (currentAttempt[0] < maxAttempts) {
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
            return;
        }
        if (!isAwaitingLoginCheck) {
            return;
        }
        isAwaitingLoginCheck = false;
        isOperationInProgress.set(true);
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
                if (!isOperationInProgress.get()) return;
                currentAttempt[0]++;
                serviceContext.evaluateJavascriptInBackground(checkLoginScript, loginStatus -> {
                    if (!isOperationInProgress.get()) return;
                    if ("\"LOGGED_IN\"".equals(loginStatus)) {
                        isOperationInProgress.set(false);
                        submitPrompt();
                    } else if ("\"NOT_LOGGED_IN\"".equals(loginStatus)) {
                        handleError("LOGIN_REQUIRED");
                    } else {
                        if (currentAttempt[0] < maxAttempts) {
                            handler.postDelayed(this, 1000L);
                        } else {
                            handleError("LOGIN_REQUIRED");
                        }
                    }
                });
            }
        });
    }

    private void submitPrompt() {
        String escapedPrompt = JSONObject.quote(finalPrompt);
        String automationScript = "(function() {" +
                "    const editorDiv = document.querySelector('div.ql-editor.new-input-ui');" +
                "    if (!editorDiv) { return 'ERROR: Editor div not found.'; }" +
                "    let pTag = editorDiv.querySelector('p');" +
                "    if (!pTag) { pTag = document.createElement('p'); editorDiv.appendChild(pTag); }" +
                "    pTag.textContent = " + escapedPrompt + ";" +
                "    const sendButton = document.querySelector('button[aria-label=\"Send message\"], button[aria-label=\"메시지 보내기\"]');" +
                "    if (!sendButton) { return 'ERROR: Send button not found.'; }" +
                "    sendButton.click();" +
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
                    String checkNewContainerScript = "(function() { return document.querySelectorAll('div.conversation-container').length > " + finalInitialCount + "; })();";
                    serviceContext.evaluateJavascriptInBackground(checkNewContainerScript, isNewContainer -> {
                        if ("true".equals(isNewContainer)) {
                            waitForResponseCompletion();
                        } else {
                            handler.postDelayed(this, 500L);
                        }
                    });
                }
            });
        });
    }

    private void waitForResponseCompletion() {
        final Handler handler = new Handler(Looper.getMainLooper());
        final String checkInProgressScript = "(function() {" +
                "    const stopButton = document.querySelector('button[aria-label=\"대답 생성 중지\"], button[aria-label=\"Stop generating response\"]');" +
                "    const pendingRequest = document.querySelector('pending-request');" +
                "    return stopButton != null || pendingRequest != null;" +
                "})();";

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pendingCallback == null) return;
                serviceContext.evaluateJavascriptInBackground(checkInProgressScript, isGenerating -> {
                    if ("true".equals(isGenerating)) {
                        handler.postDelayed(this, 1000L);
                    } else {
                        extractFinalResponse();
                    }
                });
            }
        });
    }

    private void extractFinalResponse() {
        final String extractionScript = "(function() {" +
                "    const conversations = document.querySelectorAll('div.conversation-container');" +
                "    if (conversations.length === 0) { return 'ERROR: No conversation containers found.'; }" +
                "    const lastConversation = conversations[conversations.length - 1];" +
                "    const responseElement = lastConversation.querySelector('model-response .markdown');" +
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
        isOperationInProgress.set(false);
        isAwaitingLoginCheck = false;
        // 리스너를 null로 설정하여 이 인스턴스가 더 이상 페이지 로드 이벤트를 받지 않도록 합니다.
        if (serviceContext != null) {
            serviceContext.setPageLoadListener(null);
        }
    }
}
