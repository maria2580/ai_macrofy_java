package com.example.ai_macrofy.llm.gemini_web;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.services.foreground.MyForegroundService;
import com.example.ai_macrofy.ui.WebViewActivity;
import com.example.ai_macrofy.utils.AppPreferences;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeminiWebManager implements AiModelService {
    private static final String TAG = "GeminiWebManager";
    private static final String GEMINI_URL = "https://gemini.google.com/app";
    private Context context;
    private MyForegroundService foregroundService;
    private AppPreferences appPreferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private GeminiWebHelper webHelper;
    private boolean isFirstRequest = true; // --- 추가: 첫 요청인지 판별하는 플래그 ---

    @Override
    public void setApiKey(String apiKey) {
        // Not needed for Web UI
    }

    @Override
    public void setContext(Context context) {
        this.context = context;
        this.appPreferences = new AppPreferences(context);
        if (context instanceof MyForegroundService) {
            this.foregroundService = (MyForegroundService) context;
            this.webHelper = new GeminiWebHelper(this.foregroundService);
        }
    }

    @Override
    public void generateResponse(String systemInstruction, List<ChatMessage> conversationHistory, @Nullable String currentScreenLayoutJson, @Nullable Bitmap currentScreenBitmap, @Nullable String currentScreenText, String currentUserVoiceCommand, ModelResponseCallback callback) {
        if (foregroundService == null || !foregroundService.isWebViewReady()) {
            callback.onError("WebView for Gemini Web is not ready.");
            return;
        }

        executor.execute(() -> {
            String finalPrompt = buildFinalPrompt(systemInstruction, conversationHistory, currentUserVoiceCommand);

            // --- 수정: isFirstRequest 플래그를 사용하여 첫 요청 시에만 대화 기록을 리셋합니다. ---
            if (isFirstRequest) {
                Log.d(TAG, "This is the first request. Resetting conversation tracking and starting full sequence.");
                isFirstRequest = false; // 다음 요청부터는 다른 분기를 타도록 플래그를 변경합니다.
                if (webHelper != null) {
                    webHelper.resetConversationTracking(); // 새 매크로 세션을 위해 ID 추적 리셋
                }
                webHelper.generateResponse(finalPrompt, currentScreenBitmap, callback);
            } else {
                Log.d(TAG, "This is a subsequent request. Submitting prompt directly without reloading the page.");
                // 후속 요청은 페이지 로드나 로그인 확인 없이 바로 프롬프트를 제출합니다.
                webHelper.submitPrompt(finalPrompt, currentScreenBitmap, callback);
            }
        });
    }

    private String buildFinalPrompt(String systemInstruction, List<ChatMessage> conversationHistory, String currentUserVoiceCommand) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(systemInstruction).append("\n\n");
        promptBuilder.append("## Conversation History\n");
        for (ChatMessage message : conversationHistory) {
            promptBuilder.append(message.role).append(": ").append(message.content).append("\n");
        }
        promptBuilder.append("\n## Current Task\n");
        promptBuilder.append("user: ").append(currentUserVoiceCommand).append("\n");
        promptBuilder.append("model:");
        return promptBuilder.toString();
    }

    @Override
    public String processUserCommandForPrompt(String userCommand) {
        return userCommand != null ? userCommand.trim() : "";
    }

    @Override
    public void cleanup() {
        Log.d(TAG, "Cleaning up GeminiWebManager.");
        isFirstRequest = true; // --- 추가: 서비스 종료 시 플래그를 리셋합니다. ---
        executor.shutdownNow();
    }
}