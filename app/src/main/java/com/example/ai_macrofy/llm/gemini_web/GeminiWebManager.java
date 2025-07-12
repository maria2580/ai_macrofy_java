package com.example.ai_macrofy.llm.gemini_web;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.services.foreground.MyForegroundService;

import java.util.List;

public class GeminiWebManager implements AiModelService {

    private static final String TAG = "GeminiWebManager";
    private MyForegroundService serviceContext;
    private GeminiWebHelper webHelper;

    @Override
    public void setApiKey(String apiKey) {
        // Not needed for web UI automation
    }

    @Override
    public void setContext(Context context) {
        if (context instanceof MyForegroundService) {
            this.serviceContext = (MyForegroundService) context;
        } else {
            Log.e(TAG, "Context is not an instance of MyForegroundService!");
        }
    }

    @Override
    public void generateResponse(String systemInstruction, List<ChatMessage> conversationHistory, @Nullable String currentScreenLayoutJson, @Nullable Bitmap currentScreenBitmap, @Nullable String currentScreenText, String currentUserVoiceCommand, ModelResponseCallback callback) {
        Log.d(TAG, "generateResponse called. Delegating to GeminiWebHelper.");

        if (serviceContext == null) {
            callback.onError("Service context is not set.");
            return;
        }

        String finalPrompt = buildFinalPrompt(systemInstruction, conversationHistory, currentUserVoiceCommand);

        // 웹 자동화 로직을 처리할 헬퍼 인스턴스 생성 및 실행
        webHelper = new GeminiWebHelper(serviceContext);
        webHelper.generateResponse(finalPrompt, callback);
    }

    private String buildFinalPrompt(String systemInstruction, List<ChatMessage> conversationHistory, String currentUserVoiceCommand) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(systemInstruction).append("\n\n");

        for (ChatMessage message : conversationHistory) {
            promptBuilder.append(message.role).append(": ").append(message.content).append("\n");
        }

        promptBuilder.append("user: ").append(processUserCommandForPrompt(currentUserVoiceCommand)).append("\n");
        promptBuilder.append("assistant:");

        return promptBuilder.toString();
    }


    @Override
    public String processUserCommandForPrompt(String userCommand) {
        return userCommand != null ? userCommand.trim() : "";
    }
}