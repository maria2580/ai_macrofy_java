package com.example.ai_macrofy.llm.gpt.data;

import com.google.gson.annotations.SerializedName;

public class ImageUrl {
    @SerializedName("url")
    public String url; // e.g., "data:image/jpeg;base64,{base64_image}"

    public ImageUrl(String url) {
        this.url = url;
    }
}
