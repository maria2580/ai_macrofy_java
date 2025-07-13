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

        if (!appPreferences.isGeminiWebLoggedIn()) {
            Log.w(TAG, "Not logged into Gemini Web. Prompting user to login.");
            Intent intent = new Intent(context, WebViewActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            callback.onError("User needs to log in to Gemini Web.");
            return;
        }

        executor.execute(() -> {
            String finalPrompt = buildFinalPrompt(systemInstruction, conversationHistory, currentUserVoiceCommand);

            foregroundService.loadUrlInBackground(GEMINI_URL);

            // Wait for page to load
            final AtomicBoolean pageLoaded = new AtomicBoolean(false);
            foregroundService.setPageLoadListener((view, url) -> {
                if (url.contains("gemini.google.com")) {
                    pageLoaded.set(true);
                }
            });

            try {
                // Give it a moment to start loading
                Thread.sleep(2000);
                long startTime = System.currentTimeMillis();
                while (!pageLoaded.get() && System.currentTimeMillis() - startTime < 15000) {
                    Thread.sleep(500);
                }
                foregroundService.setPageLoadListener(null); // Unset listener

                if (!pageLoaded.get()) {
                    Log.e(TAG, "Timeout waiting for Gemini page to load.");
                    callback.onError("Timeout waiting for Gemini page to load.");
                    return;
                }

                webHelper.generateResponse(finalPrompt, currentScreenBitmap, callback);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Gemini Web execution was interrupted.", e);
                callback.onError("Gemini Web execution was interrupted.");
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
        executor.shutdownNow();
    }
}