package com.example.ai_macrofy.llm.gpt.data;

import java.util.List;

public class GPTRequest {
    private final String model;
    private final List<Message> messages;

    public GPTRequest(String model, List<Message> messages) {
        this.model = model;
        this.messages = messages;
    }

    public String getModel() {
        return model;
    }

    public List<Message> getMessages() {
        return messages;
    }
}