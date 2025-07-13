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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class SharedWebViewManager {

    private static final String TAG = "SharedWebViewManager";
    private static WebView webView;
    private static Uri pendingFileUri;
    private static WindowManager windowManager;
    private static volatile boolean isWebViewAttached = false;
    private static Context applicationContext;
    private static volatile boolean isWebViewAvailable = true; // --- 추가: WebView 사용 가능 여부 플래그 ---
    private static volatile boolean isInitialized = false; // --- 추가: 초기화 실행 여부 플래그 ---

    @SuppressLint("SetJavaScriptEnabled")
    public static synchronized void init(Context context) {
        if (isInitialized) { // --- 수정: webView == null 대신 isInitialized 플래그 사용 ---
            return;
        }
        applicationContext = context.getApplicationContext();
        windowManager = (WindowManager) applicationContext.getSystemService(Context.WINDOW_SERVICE);

        // WebView는 UI 스레드에서 생성되어야 합니다.
        new Handler(Looper.getMainLooper()).post(() -> {
            try { // --- 추가: WebView 생성 오류를 잡기 위한 try-catch 블록 ---
                Log.d(TAG, "Initializing Shared WebView on main thread.");
                webView = new WebView(applicationContext);

                // 기본 설정
                WebSettings webSettings = webView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setDatabaseEnabled(true);
                webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
                webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                webView.setWebViewClient(new WebViewClient());
                webView.setWebChromeClient(new WebChromeClient());

                // 백그라운드 작업을 위해 보이지 않는 창에 연결
                attachToWindow();
                Log.d(TAG, "Shared WebView initialized successfully.");
            } catch (Exception e) {
                Log.e(TAG, "FATAL: Failed to initialize WebView. The WebView component might be missing or disabled on this device.", e);
                isWebViewAvailable = false; // --- 추가: WebView 사용 불가로 플래그 설정 ---
                webView = null;
            } finally {
                isInitialized = true; // --- 추가: 초기화 시도 완료로 플래그 설정 ---
            }
        });
    }

    public static WebView getWebView() {
        return webView;
    }

    // Context를 받는 오버로드 메서드는 이제 getWebView()로 대체될 수 있습니다.
    // 하지만 호환성을 위해 유지합니다.
    public static WebView getWebView(Context context) {
        if (!isInitialized) { // --- 수정: webView == null 대신 isInitialized 플래그 사용 ---
            init(context);
        }
        return webView;
    }

    public static void setPendingFileUri(Uri uri) {
        pendingFileUri = uri;
    }

    public static Uri getAndClearPendingFileUri() {
        Uri uri = pendingFileUri;
        pendingFileUri = null;
        return uri;
    }

    public static void onResume() {
        if (webView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                attachToWindow(); // 다시 보이지 않는 창에 연결
                webView.onResume();
            });
        }
    }

    public static void onPause() {
        if (webView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                webView.onPause();
                detachFromWindow(); // 앱이 보이지 않을 때 창에서 제거
            });
        }
    }
    public static void attachWebView(ViewGroup parent) {
        // --- 추가: WebView가 이미 다른 부모를 가지고 있는지 확인 ---
        if (webView != null && webView.getParent() != null) {
            // 이미 부모가 있다면, 먼저 기존 부모로부터 분리합니다.
            // 이는 WebViewActivity에서 MainActivity로 돌아올 때 발생할 수 있습니다.
            ((ViewGroup)webView.getParent()).removeView(webView);
        }
        if (parent != null && webView != null) {
            parent.addView(webView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            isWebViewAttached = true;
        }
    }

    public static void detachWebView(ViewGroup parent) {
        if (webView != null && webView.getParent() == parent) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Detaching shared WebView from its parent.");
                parent.removeView(webView);
            });
        }
    }
    public static void destroy() {
        if (webView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                detachFromWindow();
                webView.destroy();
                webView = null;
                applicationContext = null;
                windowManager = null;
                isWebViewAttached = false;
                isInitialized = false; // --- 추가: 파괴 시 초기화 플래그 리셋 ---
                isWebViewAvailable = true; // --- 추가: 다음 시도를 위해 플래그 리셋 ---
            });
        }
    }
    public static boolean isIsInitialized(){
        return isInitialized;
    }
    public static boolean isReady() {
        return webView != null && isWebViewAttached;
    }

    // --- 추가: WebView 사용 가능 여부를 반환하는 새 메서드 ---
    public static boolean isWebViewAvailable() {
        return isWebViewAvailable;
    }

    private static void attachToWindow() {
        if (webView == null) {
            Log.d(TAG, "WebView is null. Skipping attachment to window.");
            return;
        }

        if (webView.getParent() != null) {
            isWebViewAttached = true;
            Log.d(TAG, "WebView is already attached to a parent. Skipping new attachment.");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(applicationContext)) {
            Log.e(TAG, "Overlay permission not granted. Cannot attach WebView to window.");
            Toast.makeText(applicationContext, "Overlay permission needed for background web tasks.", Toast.LENGTH_LONG).show();
            return;
        }

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
            if (!webView.isAttachedToWindow()) {
                windowManager.addView(webView, params);
                isWebViewAttached = true;
                Log.d(TAG, "Attached background WebView to an invisible window.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error attaching WebView to window", e);
            isWebViewAttached = false;
        }
    }

    private static void detachFromWindow() {
        if (webView != null && webView.isAttachedToWindow() && !(webView.getParent() instanceof ViewGroup)) {
            try {
                windowManager.removeView(webView);
                isWebViewAttached = false;
                Log.d(TAG, "Removed background WebView from invisible window.");
            } catch (Exception e) {
                Log.e(TAG, "Error removing WebView from window", e);
            }
        }
    }
}