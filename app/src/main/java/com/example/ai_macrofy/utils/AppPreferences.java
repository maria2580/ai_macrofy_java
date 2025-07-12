package com.example.ai_macrofy.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class AppPreferences {
    private static final String PREFS_NAME = "ai_macrofy_prefs";
    private final SharedPreferences prefs;

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_GEMMA_LOCAL = "gemma_local";

    public static final String DELEGATE_CPU = "cpu";
    public static final String DELEGATE_GPU = "gpu";

    private static final String KEY_AI_PROVIDER = "ai_provider";
    private static final String KEY_OPENAI_API_KEY = "openai_api_key";
    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_PENDING_COMMAND = "pending_user_command";
    private static final String KEY_GEMMA_DELEGATE = "gemma_delegate";

    public AppPreferences(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveAiProvider(String provider) {
        prefs.edit().putString(KEY_AI_PROVIDER, provider).apply();
    }

    public String getAiProvider() {
        return prefs.getString(KEY_AI_PROVIDER, PROVIDER_OPENAI); // Default to OpenAI
    }

    public void saveOpenAiApiKey(String apiKey) {
        prefs.edit().putString(KEY_OPENAI_API_KEY, apiKey).apply();
    }

    public String getOpenAiApiKey() {
        return prefs.getString(KEY_OPENAI_API_KEY, "");
    }

    public void saveGeminiApiKey(String apiKey) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey).apply();
    }

    public String getGeminiApiKey() {
        return prefs.getString(KEY_GEMINI_API_KEY, "");
    }

    public void saveGemmaDelegate(String delegate) {
        prefs.edit().putString(KEY_GEMMA_DELEGATE, delegate).apply();
    }

    public String getGemmaDelegate() {
        return prefs.getString(KEY_GEMMA_DELEGATE, DELEGATE_CPU); // Default to CPU
    }

    public String getApiKeyForCurrentProvider() {
        String provider = getAiProvider();
        if (PROVIDER_GEMINI.equals(provider)) {
            return getGeminiApiKey();
        } else if (PROVIDER_OPENAI.equals(provider)) {
            return getOpenAiApiKey();
        }
        return ""; // Gemma local doesn't need a key
    }

    // Methods for pending command
    public void setPendingCommand(String command) {
        prefs.edit().putString(KEY_PENDING_COMMAND, command).apply();
    }

    public String getAndClearPendingCommand() {
        String command = prefs.getString(KEY_PENDING_COMMAND, null);
        if (command != null) {
            prefs.edit().remove(KEY_PENDING_COMMAND).apply();
        }
        return command;
    }
}