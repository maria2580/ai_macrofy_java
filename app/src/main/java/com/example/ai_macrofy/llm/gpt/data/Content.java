package com.example.ai_macrofy.llm.gpt.data;

import com.google.gson.annotations.SerializedName;

public class Content {
    private final String type; // "text" or "image_url"
    private String text;

    @SerializedName("image_url")
    private ImageUrl imageUrl;

    // Constructor for text content
    public Content(String type, String text, ImageUrl imageUrl) {
        this.type = type;
        this.text = text;
        this.imageUrl = imageUrl;
    }
}
