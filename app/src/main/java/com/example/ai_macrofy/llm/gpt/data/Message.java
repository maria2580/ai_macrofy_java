package com.example.ai_macrofy.llm.gpt.data;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class Message {
    @SerializedName("role")
    public String role;

    // content can be a simple string or a list of parts for vision
    @SerializedName("content")
    public Object content;

    // Constructor for simple text content
    public Message(String role, String textContent) {
        this.role = role;
        this.content = textContent;
    }

    // Constructor for vision content (list of parts)
    public Message(String role, List<ContentPart> contentParts) {
        this.role = role;
        this.content = contentParts;
    }
}