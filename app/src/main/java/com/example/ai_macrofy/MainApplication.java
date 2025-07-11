package com.example.ai_macrofy;

import android.app.Application;
import android.util.Log;

public class MainApplication extends Application {
    private static final String TAG = "MainApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application onCreate.");
        // 앱 시작 시 Gemma 모델 사전 로딩 로직 제거.
        // 모델 로딩은 이제 사용자가 매크로를 시작할 때 명시적으로 처리됩니다.
    }
}
