package com.example.ai_macrofy.llm.common;

import androidx.annotation.Nullable;
import org.json.JSONObject;

import java.util.List;

public interface AiModelService {
    void setApiKey(String apiKey);

    void generateResponse(String systemInstruction, // The main system prompt
                          List<ChatMessage> conversationHistory, // Past user/assistant messages
                          String currentScreenLayoutJson, // Current screen JSON as string
                          String currentUserVoiceCommand, // User's latest voice command
                          String previousActionContext, // String representation of action history for repetition check
                          ModelResponseCallback callback);

    String processUserCommandForPrompt(String userCommand); // Remains as is, used by managers internally
}