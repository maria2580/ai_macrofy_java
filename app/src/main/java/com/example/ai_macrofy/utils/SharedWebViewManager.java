package com.example.ai_macrofy.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

public class SharedWebViewManager {
    private static final String TAG = "SharedWebViewManager";
    private static WebView sharedWebView;
    private static WindowManager windowManager;
    private static FrameLayout windowContainer;
    private static boolean isAttachedToWindow = false;

    // --- 추가: JavaScript 콜백을 위한 인터페이스 ---
    public interface JsCallback {
        void onResult(String result);
    }

    // --- 추가: JavaScript에서 호출할 실제 객체 ---
    private static class AndroidCallback {
        private final JsCallback callback;

        AndroidCallback(JsCallback callback) {
            this.callback = callback;
        }

        @JavascriptInterface
        public void onResult(String result) {
            // Ensure callback is run on the main thread
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) {
                    callback.onResult(result);
                }
            });
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private static void initializeWebView(Context context) {
        if (sharedWebView == null) {
            try {
                Log.d(TAG, "Attempting to initialize new WebView instance.");
                sharedWebView = new WebView(context.getApplicationContext());
                WebSettings settings = sharedWebView.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                // --- JavaScript 인터페이스 추가 ---
                // 이 라인은 getWebView() 또는 evaluateJavascriptWithCallback()을 호출하기 전에
                // 콜백을 설정하는 로직이 필요함을 의미합니다.
                // sharedWebView.addJavascriptInterface(new Object(), "AndroidCallback"); // Placeholder

                sharedWebView.setWebViewClient(new WebViewClient());
                sharedWebView.setWebChromeClient(new WebChromeClient());
                Log.d(TAG, "WebView initialized successfully.");
            } catch (Exception e) {
                // Catch exceptions during WebView instantiation, which can happen if
                // the WebView provider is missing or disabled on the device.
                Log.e(TAG, "Failed to initialize WebView. Web-based features will be unavailable.", e);
                sharedWebView = null; // Ensure webView is null so isWebViewAvailable() returns false.
            }
        }
    }

    public static WebView getWebView() {
        return sharedWebView;
    }

    // --- 수정: getWebView가 Context를 받도록 하고, 필요시 init을 호출 ---
    public static synchronized WebView getWebView(Context context) {
        if (sharedWebView == null) {
            initializeWebView(context);
        }
        return sharedWebView;
    }

    // --- 추가: isReady() 메서드 ---
    public static boolean isReady() {
        return sharedWebView != null && isAttachedToWindow;
    }

    // --- 수정: isWebViewAvailable이 공유 인스턴스를 확인하도록 변경 ---
    public static boolean isWebViewAvailable() {
        return sharedWebView != null;
    }

    public static void attachWebView(FrameLayout container) {
        if (sharedWebView != null && sharedWebView.getParent() == null) {
            container.addView(sharedWebView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            isAttachedToWindow = true;
        }
    }

    public static void detachWebView(FrameLayout container) {
        if (sharedWebView != null && sharedWebView.getParent() == container) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Detaching shared WebView from its parent.");
                container.removeView(sharedWebView);
                // isAttached는 WindowManager에서 제거될 때 false로 설정됩니다.
            });
        }
    }

    // --- 추가: WindowManager에 직접 연결 및 제거하는 메서드 ---
    public static void attachToWindow(Context context) {
        if (sharedWebView == null) {
            Log.e(TAG, "WebView is null, cannot attach to window.");
            return;
        }
        if (isAttachedToWindow || sharedWebView.getParent() != null) {
            Log.d(TAG, "WebView is already attached.");
            isAttachedToWindow = true; // 상태 동기화
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Overlay permission not granted.");
            return;
        }
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSPARENT
        );
        params.gravity = Gravity.START | Gravity.TOP;
        try {
            windowManager.addView(sharedWebView, params);
            isAttachedToWindow = true;
            Log.d(TAG, "Attached background WebView to an invisible window.");
        } catch (Exception e) {
            Log.e(TAG, "Error attaching WebView to window", e);
        }
    }

    public static void detachFromWindow(Context context) {
        if (sharedWebView != null && isAttachedToWindow && sharedWebView.isAttachedToWindow() && !(sharedWebView.getParent() instanceof ViewGroup)) {
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            try {
                windowManager.removeView(sharedWebView);
                isAttachedToWindow = false;
                Log.d(TAG, "Removed background WebView from invisible window.");
            } catch (Exception e) {
                Log.e(TAG, "Error removing WebView from window", e);
            }
        }
    }


    public static void onResume() {
        if (sharedWebView != null) {
            sharedWebView.onResume();
        }
    }

    public static void onPause() {
        if (sharedWebView != null) {
            sharedWebView.onPause();
        }
    }

    // --- 추가: 앱 종료 시 호출될 destroy 메서드 ---
    public static void destroy() {
        if (sharedWebView != null) {
            // Ensure it's detached from any window
            if (sharedWebView.getParent() instanceof ViewGroup) {
                ((ViewGroup) sharedWebView.getParent()).removeView(sharedWebView);
            }
            sharedWebView.destroy();
            sharedWebView = null;
            isAttachedToWindow = false;
            Log.d(TAG, "Shared WebView destroyed.");
        }
    }

    // --- 추가: 콜백과 함께 JavaScript를 실행하는 메서드 ---
    public static void evaluateJavascriptWithCallback(String script, JsCallback callback) {
        if (sharedWebView == null) {
            if (callback != null) {
                callback.onResult("ERROR: WebView is not initialized.");
            }
            return;
        }
        // 콜백을 위한 JavaScript 인터페이스를 설정합니다.
        sharedWebView.addJavascriptInterface(new AndroidCallback(callback), "AndroidCallback");
        sharedWebView.evaluateJavascript(script, null); // 결과는 콜백을 통해 비동기적으로 처리됩니다.
    }

    public static void evaluateJavascriptInBackground(String script, ValueCallback<String> callback) {
        if (sharedWebView != null) {
            sharedWebView.evaluateJavascript(script, callback);
        }
    }

    // --- 추가: WebView가 로드 중인지 여부를 확인하는 메서드 ---
    public static boolean isLoading() {
        return sharedWebView != null && sharedWebView.getVisibility() == View.VISIBLE;
    }

    // --- 추가: WebView의 현재 URL 가져오기 ---
    public static String getCurrentUrl() {
        if (sharedWebView != null) {
            String url = sharedWebView.getUrl();
            return url != null ? url : "about:blank";
        }
        return "about:blank";
    }

    // --- 추가: WebView에서 페이지 새로 고침 ---
    public static void reload() {
        if (sharedWebView != null) {
            sharedWebView.reload();
        }
    }

    // --- 추가: WebView에서 뒤로 가기 ---
    public static boolean goBack() {
        if (sharedWebView != null && sharedWebView.canGoBack()) {
            sharedWebView.goBack();
            return true;
        }
        return false;
    }

    // --- 추가: WebView에서 앞으로 가기 ---
    public static boolean goForward() {
        if (sharedWebView != null && sharedWebView.canGoForward()) {
            sharedWebView.goForward();
            return true;
        }
        return false;
    }

    // --- 추가: WebView에서 특정 URL 로드 ---
    public static void loadUrl(String url) {
        if (sharedWebView != null) {
            sharedWebView.loadUrl(url);
        }
    }

    // --- 추가: WebView에서 HTML 데이터 로드 ---
    public static void loadData(String data, String mimeType, String encoding) {
        if (sharedWebView != null) {
            sharedWebView.loadData(data, mimeType, encoding);
        }
    }

    // --- 추가: WebView에서 JavaScript 실행 ---
    public static void evaluateJavascript(String script) {
        if (sharedWebView != null) {
            sharedWebView.evaluateJavascript(script, null);
        }
    }
}