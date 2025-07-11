package com.example.ai_macrofy.llm.gpt.data;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class GPTRequest {
    @SerializedName("model")
    private String model;

    @SerializedName("messages")
    private List<Message> messages;

    public GPTRequest(String model, List<Message> messages) {
        this.model = model;
        this.messages = messages;
    }

    // Getters and setters if needed
}