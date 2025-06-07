package com.example.ai_macrofy.llm.common;

public interface ModelResponseCallback {
    void onSuccess(String response);
    void onError(String error);
}