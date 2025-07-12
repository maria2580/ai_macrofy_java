package com.example.ai_macrofy.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;

public class SharedWebViewManager {

    private static final String TAG = "SharedWebViewManager";
    private static volatile WebView sharedWebView;
    private static final Object lock = new Object();

    @SuppressLint("SetJavaScriptEnabled")
    public static void init(Context context) {
        // WebView는 반드시 메인 스레드에서 생성되어야 합니다.
        // 이 메서드는 MainApplication.onCreate()에서 호출됩니다.
        if (sharedWebView == null) {
            synchronized (lock) {
                if (sharedWebView == null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            Log.d(TAG, "Creating new shared WebView instance.");
                            WebView webView = new WebView(context.getApplicationContext());
                            webView.getSettings().setJavaScriptEnabled(true);
                            webView.getSettings().setDomStorageEnabled(true);
                            webView.getSettings().setDatabaseEnabled(true);
                            // 백그라운드 실행 및 자동 창 열기 허용 설정 추가
                            webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);


                            CookieManager cookieManager = CookieManager.getInstance();
                            cookieManager.setAcceptCookie(true);
                            cookieManager.setAcceptThirdPartyCookies(webView, true);
                            sharedWebView = webView;
                            Log.d(TAG, "Shared WebView created successfully.");
                        } catch (Exception e) {
                            Log.e(TAG, "FATAL: Failed to create shared WebView.", e);
                            // 이 경우 WebView 기능은 사용할 수 없게 됩니다.
                        }
                    });
                }
            }
        }
    }

    public static WebView getWebView() {
        return sharedWebView;
    }

    // WebView의 생명주기를 관리하는 메서드 추가
    public static void onResume() {
        if (sharedWebView != null) {
            Log.d(TAG, "Resuming shared WebView.");
            sharedWebView.onResume();
        }
    }

    public static void onPause() {
        if (sharedWebView != null) {
            Log.d(TAG, "Pausing shared WebView.");
            sharedWebView.onPause();
        }
    }

    public static void attachWebView(ViewGroup parent) {
        if (sharedWebView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                // WebView가 이미 다른 부모에 붙어있다면, 먼저 제거합니다.
                if (sharedWebView.getParent() instanceof ViewGroup) {
                    Log.w(TAG, "WebView is already attached to a parent. Detaching first.");
                    ((ViewGroup) sharedWebView.getParent()).removeView(sharedWebView);
                }
                Log.d(TAG, "Attaching shared WebView to new parent.");
                parent.addView(sharedWebView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            });
        } else {
            Log.e(TAG, "Cannot attach WebView, it is null.");
        }
    }

    public static void detachWebView(ViewGroup parent) {
        if (sharedWebView != null && sharedWebView.getParent() == parent) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Detaching shared WebView from its parent.");
                parent.removeView(sharedWebView);
            });
        }
    }

    // 앱 종료 시 호출될 수 있는 정리 메서드
    public static void destroyWebView() {
        if (sharedWebView != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d(TAG, "Destroying shared WebView.");
                sharedWebView.destroy();
                sharedWebView = null;
            });
        }
    }
}
