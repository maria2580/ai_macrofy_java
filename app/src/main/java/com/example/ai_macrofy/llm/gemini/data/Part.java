package com.example.ai_macrofy.llm.gemini.data;

import com.google.gson.annotations.SerializedName;

public class Part {
    @SerializedName("text")
    private String text;

    @SerializedName("inline_data")
    private InlineData inlineData;

    // Constructor for text part
    public Part(String text) {
        this.text = text;
    }

    // Constructor for image part
    public Part(InlineData inlineData) {
        this.inlineData = inlineData;
    }

    public String getText() {
        return text;
    }
}