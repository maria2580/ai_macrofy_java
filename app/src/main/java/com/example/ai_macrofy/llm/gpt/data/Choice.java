package com.example.ai_macrofy.llm.gpt.data;

public class Choice {
    private final Message message;

    public Choice(Message message) {
        this.message = message;
    }

    public Message getMessage() {
        return message;
    }
}