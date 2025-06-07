package com.example.ai_macrofy.llm.gemini.data;

import java.util.List;

// Gemini API의 Content 구조에 해당
public class Message { // 이름을 ContentRepresentation 또는 GeminiContent 등으로 변경하는 것을 고려
    private List<Part> parts;
    private String role; // "user" 또는 "model"

    public Message(List<Part> parts, String role) {
        this.parts = parts;
        this.role = role;
    }

    public List<Part> getParts() {
        return parts;
    }

    public String getRole() {
        return role;
    }
}