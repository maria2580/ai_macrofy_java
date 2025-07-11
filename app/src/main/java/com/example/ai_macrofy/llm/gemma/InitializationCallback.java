package com.example.ai_macrofy.llm.gemma;

public interface InitializationCallback {
    void onInitSuccess();
    void onInitFailure(String error);
}
