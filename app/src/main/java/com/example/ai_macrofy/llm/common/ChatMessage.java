package com.example.ai_macrofy.llm.common;

public class ChatMessage {
    public final String role; // e.g., "user", "assistant" (or "model" for Gemini)
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}