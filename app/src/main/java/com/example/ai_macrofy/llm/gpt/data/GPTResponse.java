package com.example.ai_macrofy.llm.gpt.data;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class GPTResponse {
    @SerializedName("choices")
    private List<Choice> choices;

    public List<Choice> getChoices() {
        return choices;
    }

    public String getFirstChoiceMessageContent() {
        if (choices != null && !choices.isEmpty()) {
            Choice firstChoice = choices.get(0);
            if (firstChoice != null && firstChoice.getMessage() != null) {
                // Assuming content is always a string in the response
                return (String) firstChoice.getMessage().content;
            }
        }
        return null;
    }
}