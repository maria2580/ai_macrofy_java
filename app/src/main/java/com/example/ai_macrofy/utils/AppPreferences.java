package com.example.ai_macrofy.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class AppPreferences {

    public static final String PREFS_NAME = "AiMacrofyPrefs";
    public static final String KEY_AI_PROVIDER = "ai_provider";
    public static final String KEY_OPENAI_API_KEY = "openai_api_key";
    public static final String KEY_GEMINI_API_KEY = "gemini_api_key";

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_GEMINI = "gemini";

    private final SharedPreferences sharedPreferences;

    public AppPreferences(Context context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveAiProvider(String provider) {
        sharedPreferences.edit().putString(KEY_AI_PROVIDER, provider).apply();
    }

    public String getAiProvider() {
        return sharedPreferences.getString(KEY_AI_PROVIDER, PROVIDER_OPENAI); // 기본값 OpenAI
    }

    public void saveOpenAiApiKey(String apiKey) {
        sharedPreferences.edit().putString(KEY_OPENAI_API_KEY, apiKey).apply();
    }

    public String getOpenAiApiKey() {
        return sharedPreferences.getString(KEY_OPENAI_API_KEY, "");
    }

    public void saveGeminiApiKey(String apiKey) {
        sharedPreferences.edit().putString(KEY_GEMINI_API_KEY, apiKey).apply();
    }

    public String getGeminiApiKey() {
        return sharedPreferences.getString(KEY_GEMINI_API_KEY, "");
    }

    public String getApiKeyForCurrentProvider() {
        String provider = getAiProvider();
        if (PROVIDER_GEMINI.equals(provider)) {
            return getGeminiApiKey();
        }
        return getOpenAiApiKey(); // Default to OpenAI
    }
}