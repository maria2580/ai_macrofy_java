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
import android.widget.FrameLayout;
import android.widget.Toast;

public class SharedWebViewManager {
    private static final String TAG = "SharedWebViewManager";
    private static volatile WebView webView; // --- volatile 추가 ---
    private static volatile boolean isAttached = false; // --- volatile 추가 및 이동 ---

    @SuppressLint("SetJavaScriptEnabled")
    public static synchronized void init(Context context) { // --- synchronized 추가 ---
        if (webView == null) {
            try {
                Log.d(TAG, "Attempting to initialize new WebView instance.");
                webView = new WebView(context.getApplicationContext());
                WebSettings webSettings = webView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setDatabaseEnabled(true);
                webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
                webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                webView.setWebViewClient(new WebViewClient());
                webView.setWebChromeClient(new WebChromeClient());
                Log.d(TAG, "WebView initialized successfully.");
            } catch (Exception e) {
                // Catch exceptions during WebView instantiation, which can happen if
                // the WebView provider is missing or disabled on the device.
                Log.e(TAG, "Failed to initialize WebView. Web-based features will be unavailable.", e);
                webView = null; // Ensure webView is null so isWebViewAvailable() returns false.
            }
        }
    }

    public static WebView getWebView() {
        return webView;
    }

    // --- 수정: getWebView가 Context를 받도록 하고, 필요시 init을 호출 ---
    public static synchronized WebView getWebView(Context context) {
        if (webView == null) {
            init(context);
        }
        return webView;
    }

    // --- 추가: isReady() 메서드 ---
    public static boolean isReady() {
        return webView != null && isAttached;
    }

    // --- 수정: isWebViewAvailable이 공유 인스턴스를 확인하도록 변경 ---
    public static boolean isWebViewAvailable() {
        return webView != null;
    }

    public static void attachWebView(FrameLayout container) {
        if (webView != null && webView.getParent() == null) {
            container.addView(webView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            isAttached = true;
        }
    }

    public static void detachWebView(FrameLayout container) {
        if (webView != null && webView.getParent() == container) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Detaching shared WebView from its parent.");
                container.removeView(webView);
                // isAttached는 WindowManager에서 제거될 때 false로 설정됩니다.
            });
        }
    }

    // --- 추가: WindowManager에 직접 연결 및 제거하는 메서드 ---
    public static void attachToWindow(Context context) {
        if (webView == null) {
            Log.e(TAG, "WebView is null, cannot attach to window.");
            return;
        }
        if (isAttached || webView.getParent() != null) {
            Log.d(TAG, "WebView is already attached.");
            isAttached = true; // 상태 동기화
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.e(TAG, "Overlay permission not granted.");
            return;
        }
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
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
            windowManager.addView(webView, params);
            isAttached = true;
            Log.d(TAG, "Attached background WebView to an invisible window.");
        } catch (Exception e) {
            Log.e(TAG, "Error attaching WebView to window", e);
        }
    }

    public static void detachFromWindow(Context context) {
        if (webView != null && isAttached && webView.isAttachedToWindow() && !(webView.getParent() instanceof ViewGroup)) {
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            try {
                windowManager.removeView(webView);
                isAttached = false;
                Log.d(TAG, "Removed background WebView from invisible window.");
            } catch (Exception e) {
                Log.e(TAG, "Error removing WebView from window", e);
            }
        }
    }


    public static void onResume() {
        if (webView != null) {
            webView.onResume();
        }
    }

    public static void onPause() {
        if (webView != null) {
            webView.onPause();
        }
    }

    // --- 추가: 앱 종료 시 호출될 destroy 메서드 ---
    public static void destroy() {
        if (webView != null) {
            // Ensure it's detached from any window
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
            isAttached = false;
            Log.d(TAG, "Shared WebView destroyed.");
        }
    }
}