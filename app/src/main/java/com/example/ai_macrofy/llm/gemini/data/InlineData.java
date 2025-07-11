package com.example.ai_macrofy.llm.gemini.data;

import com.google.gson.annotations.SerializedName;

public class InlineData {
    @SerializedName("mime_type")
    private final String mimeType;
    private final String data;

    public InlineData(String mimeType, String data) {
        this.mimeType = mimeType;
        this.data = data;
    }
}
