package com.example.ai_macrofy.llm.gemini.data;

import java.util.List;

public class Message {
    private List<Part> parts;
    private String role; // "user" or "model"

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

    // Convenience method to get text from the first part of a response message
    public String getFirstPartText() {
        if (parts != null && !parts.isEmpty()) {
            Part firstPart = parts.get(0);
            if (firstPart != null) {
                return firstPart.getText();
            }
        }
        return null;
    }
}