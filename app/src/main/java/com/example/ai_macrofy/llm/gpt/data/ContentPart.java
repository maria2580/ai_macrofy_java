package com.example.ai_macrofy.llm.gpt.data;

import com.google.gson.annotations.SerializedName;

public class ContentPart {
    @SerializedName("type")
    public String type; // "text" or "image_url"

    @SerializedName("text")
    public String text; // Only for type "text"

    @SerializedName("image_url")
    public ImageUrl imageUrl; // Only for type "image_url"

    public ContentPart(String type, String text, ImageUrl imageUrl) {
        this.type = type;
        this.text = text;
        this.imageUrl = imageUrl;
    }
}
