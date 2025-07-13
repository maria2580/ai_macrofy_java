package com.example.ai_macrofy.llm.common;

import android.graphics.Bitmap; // Import Bitmap
import androidx.annotation.Nullable;
import org.json.JSONObject;
import android.content.Context;

import java.util.List;

public interface AiModelService {
    void setApiKey(String apiKey);
    void setContext(Context context); // Context for file operations, etc.

    void generateResponse(String systemInstruction, // The main system prompt
                          List<ChatMessage> conversationHistory, // Past user/assistant messages
                          @Nullable String currentScreenLayoutJson, // No longer used by new flow, but kept for compatibility
                          @Nullable Bitmap currentScreenBitmap, // The screenshot
                          @Nullable String currentScreenText, // New parameter for screen text
                          String currentUserVoiceCommand, // User's latest voice command
                          ModelResponseCallback callback);

    String processUserCommandForPrompt(String userCommand); // Remains as is, used by managers internally

    void cleanup();
}