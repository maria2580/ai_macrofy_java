package com.example.ai_macrofy.llm.gemini.data;

// Gemini API의 Part 구조에 해당
public class Part {
    private String text;
    // 이미지를 다루려면 inline_data 등 다른 필드 추가 가능

    public Part(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }
}