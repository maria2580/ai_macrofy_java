package com.example.ai_macrofy;

import android.app.Application;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;

import com.example.ai_macrofy.utils.AppPreferences;
import com.example.ai_macrofy.utils.SharedWebViewManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainApplication extends Application {
    private static final String TAG = "MainApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        // 앱 시작 시 공유 WebView를 안정적으로 초기화합니다.
        // 이 작업은 UI 스레드에서 WebView를 생성하므로 앱 전체에서 안전하게 사용할 수 있습니다.
        SharedWebViewManager.init(this);

        // 앱 시작 시 백그라운드 스레드에서 앱 목록을 캐싱합니다.
        new Thread(this::cacheAppList).start();
    }

    private void cacheAppList() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        Log.d(TAG, "Starting to cache app list...");
        LauncherApps launcherApps = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps == null) {
            Log.e(TAG, "Could not get LauncherApps service.");
            return;
        }

        Map<String, String> launchableApps = new HashMap<>();
        Set<String> addedPackages = new HashSet<>();
        PackageManager pm = getPackageManager();

        List<UserHandle> profiles = launcherApps.getProfiles();
        for (UserHandle profile : profiles) {
            List<LauncherActivityInfo> apps = launcherApps.getActivityList(null, profile);
            for (LauncherActivityInfo app : apps) {
                String packageName = app.getApplicationInfo().packageName;
                if (!addedPackages.contains(packageName)) {
                    String appName;
                    try {
                        // 1. 기본 방법으로 앱 이름(레이블)을 가져옵니다.
                        appName = app.getLabel().toString();
                    } catch (Exception e) {
                        // 2. 예외 발생 시(로그에서 확인된 문제), PackageManager를 통해 다시 시도합니다.
                        Log.w(TAG, "getLabel() failed for " + packageName + ". Retrying with PackageManager.", e);
                        try {
                            appName = app.getApplicationInfo().loadLabel(pm).toString();
                        } catch (Exception e2) {
                            // 3. 이마저도 실패하면 패키지 이름을 앱 이름으로 사용합니다.
                            Log.e(TAG, "loadLabel() also failed for " + packageName + ". Using package name as fallback.", e2);
                            appName = packageName;
                        }
                    }
                    launchableApps.put(packageName, appName);
                    addedPackages.add(packageName);
                }
            }
        }

        AppPreferences appPreferences = new AppPreferences(this);
        appPreferences.saveAppList(launchableApps);
        Log.d(TAG, "App list cached successfully. Total apps: " + launchableApps.size());
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // 앱 종료 시 공유 WebView 리소스를 정리합니다.
        SharedWebViewManager.destroy();
    }
}
