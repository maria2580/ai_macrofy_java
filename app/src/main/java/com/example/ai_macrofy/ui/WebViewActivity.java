package com.example.ai_macrofy.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.ai_macrofy.R;
import com.example.ai_macrofy.utils.AppPreferences;
import com.example.ai_macrofy.utils.SharedWebViewManager;

public class WebViewActivity extends AppCompatActivity {

    private static final String TAG = "WebViewActivity";
    public static final String ACTION_GEMINI_LOGIN_SUCCESS = "com.example.ai_macrofy.ACTION_GEMINI_LOGIN_SUCCESS";
    private WebView webView;
    private AppPreferences appPreferences;
    private FrameLayout webViewContainer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);
        Log.d(TAG, "WebViewActivity created to handle user login.");

        appPreferences = new AppPreferences(this);
        webViewContainer = findViewById(R.id.webview_container);

        // SharedWebViewManager에서 WebView 인스턴스를 가져옵니다.
        webView = SharedWebViewManager.getWebView();
        if (webView == null) {
            Toast.makeText(this, "WebView could not be initialized. Please restart the app.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // WebView를 현재 Activity의 레이아웃에 추가합니다.
        SharedWebViewManager.attachWebView(webViewContainer);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished loading: " + url);
                // 로그인 성공 여부를 판단하는 로직 (예: URL에 'app'이 포함되는지)
                if (url.contains("gemini.google.com/app")) {
                    appPreferences.setGeminiWebLoggedIn(true);
                    invalidateOptionsMenu(); // 메뉴를 다시 그리도록 하여 'Login Complete' 버튼 표시
                }
            }
        });

        // 이미 로그인 되어 있을 수 있으므로, 현재 URL을 확인하거나 새로 로드합니다.
        String currentUrl = webView.getUrl();
        if (currentUrl == null || !currentUrl.startsWith("https://gemini.google.com")) {
            webView.loadUrl("https://gemini.google.com");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Activity가 화면에 보일 때 WebView를 활성화합니다.
        SharedWebViewManager.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Activity가 화면에서 사라질 때 WebView를 일시정지합니다.
        SharedWebViewManager.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.webview_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem loginCompleteItem = menu.findItem(R.id.action_login_complete);
        if (loginCompleteItem != null) {
            // AppPreferences에 저장된 로그인 상태에 따라 버튼 가시성 조절
            loginCompleteItem.setVisible(appPreferences.isGeminiWebLoggedIn());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_login_complete) {
            Log.d(TAG, "Login Complete button clicked by user.");
            // 로그인 상태 저장
            appPreferences.setGeminiWebLoggedIn(true);
            // 쿠키 강제 저장
            CookieManager.getInstance().flush();
            // 로그인 성공 브로드캐스트 전송
            Intent intent = new Intent(ACTION_GEMINI_LOGIN_SUCCESS);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            // 사용자에게 성공 메시지 표시
            Toast.makeText(this, "Gemini login successful!", Toast.LENGTH_SHORT).show();
            finish(); // Activity 종료
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        // Activity가 파괴될 때 WebView를 레이아웃에서 분리합니다.
        if (webViewContainer != null) {
            SharedWebViewManager.detachWebView(webViewContainer);
        }
        super.onDestroy();
        Log.d(TAG, "WebViewActivity destroyed and WebView detached.");
    }
}
