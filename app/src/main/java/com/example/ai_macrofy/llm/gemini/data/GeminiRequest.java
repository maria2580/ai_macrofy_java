package com.example.ai_macrofy.llm.gemini.data;

import java.util.List;
import com.google.gson.annotations.SerializedName;

public class GeminiRequest {
    private List<Message> contents;

    @SerializedName("system_instruction")
    private Message systemInstruction;

    @SerializedName("generation_config")
    private GenerationConfig generationConfig;

    // safetySettings 등 다른 필드도 추가 가능

    public GeminiRequest(List<Message> contents, Message systemInstruction, GenerationConfig generationConfig) {
        this.contents = contents;
        this.systemInstruction = systemInstruction;
        this.generationConfig = generationConfig;
    }

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

    public Message getSystemInstruction() {
        return systemInstruction;
    }

    public void setSystemInstruction(Message systemInstruction) {
        this.systemInstruction = systemInstruction;
    }
}