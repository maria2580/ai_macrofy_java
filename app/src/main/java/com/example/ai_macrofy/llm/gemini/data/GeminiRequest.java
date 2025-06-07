package com.example.ai_macrofy.llm.gemini.data;

import java.util.List;
import com.google.gson.annotations.SerializedName;

public class GeminiRequest {
    private List<Message> contents; // 이전과 동일

    @SerializedName("generation_config") // JSON 필드명과 일치
    private GenerationConfig generationConfig;

    // safetySettings 등 다른 필드도 추가 가능

    // 기본 생성자 (Gson)
    public GeminiRequest() {}

    public GeminiRequest(List<Message> contents, GenerationConfig generationConfig) {
        this.contents = contents;
        this.generationConfig = generationConfig;
    }

    public List<Message> getContents() {
        return contents;
    }

    public void setContents(List<Message> contents) {
        this.contents = contents;
    }

    public GenerationConfig getGenerationConfig() {
        return generationConfig;
    }

    public void setGenerationConfig(GenerationConfig generationConfig) {
        this.generationConfig = generationConfig;
    }
}