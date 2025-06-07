package com.example.ai_macrofy.llm.gemini.data;

import com.google.gson.annotations.SerializedName;

public class ThinkingConfig {
    @SerializedName("thinking_budget") // JSON 필드명과 일치
    private Integer thinkingBudget; // int 또는 Integer, null 허용 여부에 따라

    // 기본 생성자 (Gson)
    public ThinkingConfig() {}

    public ThinkingConfig(Integer thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
    }

    public Integer getThinkingBudget() {
        return thinkingBudget;
    }

    public void setThinkingBudget(Integer thinkingBudget) {
        this.thinkingBudget = thinkingBudget;
    }
}