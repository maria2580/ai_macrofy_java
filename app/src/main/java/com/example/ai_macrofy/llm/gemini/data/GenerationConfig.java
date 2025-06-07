package com.example.ai_macrofy.llm.gemini.data;

import com.google.gson.annotations.SerializedName;

public class GenerationConfig {
    // temperature, topP, topK, maxOutputTokens, stopSequences 등 다른 필드도 추가 가능

    @SerializedName("thinking_config") // JSON 필드명과 일치
    private ThinkingConfig thinkingConfig;

    // 기본 생성자 (Gson)
    public GenerationConfig() {}

    public GenerationConfig(ThinkingConfig thinkingConfig) {
        this.thinkingConfig = thinkingConfig;
    }

    public ThinkingConfig getThinkingConfig() {
        return thinkingConfig;
    }

    public void setThinkingConfig(ThinkingConfig thinkingConfig) {
        this.thinkingConfig = thinkingConfig;
    }
}