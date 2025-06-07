package com.example.ai_macrofy.llm.gpt.data;

import java.util.List;

public class GPTResponse {
    private final List<Choice> choices;

    public GPTResponse(List<Choice> choices) {
        this.choices = choices;
    }

    public List<Choice> getChoices() {
        return choices;
    }
}