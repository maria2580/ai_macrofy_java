package com.example.ai_macrofy.llm.gemini;

import android.util.Log;

import androidx.annotation.Nullable;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage; // Import common ChatMessage
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
// Gemini data classes
import com.example.ai_macrofy.llm.gemini.data.Choice; //
import com.example.ai_macrofy.llm.gemini.data.GenerationConfig;
import com.example.ai_macrofy.llm.gemini.data.GeminiRequest;
import com.example.ai_macrofy.llm.gemini.data.GeminiResponse;
import com.example.ai_macrofy.llm.gemini.data.Message; // This is Gemini's Message
import com.example.ai_macrofy.llm.gemini.data.Part;
import com.example.ai_macrofy.llm.gemini.data.ThinkingConfig;


import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class GeminiManager implements AiModelService {
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/";
    private String apiKeyInstance; // Instance-specific API key

    // Model name can be made configurable
    private static final String DEFAULT_MODEL_NAME = "gemini-2.5-flash-preview-05-20"; // Updated to a common model

    private static volatile Retrofit retrofitInstanceGemini; // Shared Retrofit
    private GeminiApi apiInstanceInternalGemini; // Instance-specific API service

    public GeminiManager() {
        // Constructor
    }

    private GeminiApi getApiForInstance() {
        if (apiInstanceInternalGemini == null) {
            if (retrofitInstanceGemini == null) {
                synchronized (GeminiManager.class) {
                    if (retrofitInstanceGemini == null) {
                        retrofitInstanceGemini = new Retrofit.Builder()
                                .baseUrl(BASE_URL)
                                .addConverterFactory(GsonConverterFactory.create())
                                .build();
                    }
                }
            }
            apiInstanceInternalGemini = retrofitInstanceGemini.create(GeminiApi.class);
        }
        return apiInstanceInternalGemini;
    }

    @Override
    public void setApiKey(String apiKey) {
        this.apiKeyInstance = apiKey;
    }

    // This internal method now takes the structured list of Gemini Messages
    private void callGeminiApi(String modelName, List<com.example.ai_macrofy.llm.gemini.data.Message> geminiApiContents, @Nullable Integer thinkingBudget, ModelResponseCallback callback) {
        if (apiKeyInstance == null || apiKeyInstance.isEmpty()) {
            callback.onError("Gemini API key is not set for this instance");
            return;
        }
        if (geminiApiContents == null || geminiApiContents.isEmpty()) {
            callback.onError("Content for Gemini is empty");
            return;
        }

        GenerationConfig generationConfig = null;
        if (thinkingBudget != null) {
            ThinkingConfig thinkingConfig = new ThinkingConfig(thinkingBudget); //
            generationConfig = new GenerationConfig(thinkingConfig); //
        }

        GeminiRequest request = new GeminiRequest(geminiApiContents, generationConfig); //

        Log.d("GeminiManager", "Sending request to Gemini model: " + modelName + " with " + geminiApiContents.size() + " content items."
                + (generationConfig != null && generationConfig.getThinkingConfig() != null ? " with thinkingBudget: " + generationConfig.getThinkingConfig().getThinkingBudget() : "")); //

        getApiForInstance().generateContent(modelName, apiKeyInstance, request).enqueue(new Callback<GeminiResponse>() { //
            @Override
            public void onResponse(Call<GeminiResponse> call, retrofit2.Response<GeminiResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    GeminiResponse geminiResponse = response.body(); //
                    String responseText = geminiResponse.getFirstCandidateText(); //

                    if (responseText != null && !responseText.isEmpty()) {
                        Log.d("GeminiManager", "Gemini onSuccess (Text): " + responseText);
                        callback.onSuccess(responseText);
                    } else {
                        Log.w("GeminiManager", "Gemini response successful but no valid text content found. Checking candidates...");
                        if (geminiResponse.getCandidates() != null && !geminiResponse.getCandidates().isEmpty()) { //
                            com.example.ai_macrofy.llm.gemini.data.Choice firstCandidate = geminiResponse.getCandidates().get(0); //
                            Log.w("GeminiManager", "First candidate finishReason: " + firstCandidate.getFinishReason()); //
                        }
                        callback.onError("No valid message content in response from Gemini. Possible filtering or empty/non-text response.");
                    }
                } else {
                    String errorBodyString = "Unknown error";
                    if (response.errorBody() != null) {
                        try {
                            errorBodyString = response.errorBody().string();
                        } catch (IOException e) {
                            Log.e("GeminiManager", "Error reading error body", e);
                        }
                    }
                    Log.e("GeminiManager", "Gemini API Error: " + response.message() + " (Code: " + response.code() + ") Body: " + errorBodyString);
                    callback.onError("Gemini API Error: " + response.message() + " (Code: " + response.code() + ") Body: " + errorBodyString);
                }
            }

            @Override
            public void onFailure(Call<GeminiResponse> call, Throwable t) {
                Log.e("GeminiManager", "Gemini API call onFailure: " + t.getMessage(), t);
                callback.onError("Gemini Network Error: " + t.getMessage());
            }
        });
    }


    @Override
    public String processUserCommandForPrompt(String userCommand) { //
        return userCommand != null ? userCommand.trim() : ""; //
    }

    @Override
    public void generateResponse(String systemInstruction,
                                 List<ChatMessage> conversationHistory,
                                 String currentScreenLayoutJson,
                                 String currentUserVoiceCommand,
                                 String previousActionContext,
                                 ModelResponseCallback callback) {

        List<com.example.ai_macrofy.llm.gemini.data.Message> geminiApiContents = new ArrayList<>();

        String currentTurnUserRawContent =
                "Current Screen Layout Information:\n" + currentScreenLayoutJson + "\n\n" +
                        "Previous Action Context for Repetition Check:\n" + previousActionContext + "\n\n" +
                        "User's Current Command/Question:\n" + processUserCommandForPrompt(currentUserVoiceCommand);

        String processedSystemInstruction = systemInstruction;

        // conversationHistory가 비어있고, systemInstruction을 첫 번째 user 메시지에 포함하는 경우
        boolean systemInstructionPrepended = false;

        if (conversationHistory.isEmpty()) {
            List<Part> parts = new ArrayList<>();
            parts.add(new Part(systemInstruction + "\n\n" + currentTurnUserRawContent));
            geminiApiContents.add(new com.example.ai_macrofy.llm.gemini.data.Message(parts, "user"));
            systemInstructionPrepended = true;
        } else {
            // 시스템 지침을 대화의 시작 부분에 명시적으로 추가 (Gemini는 system role이 별도로 없을 수 있음)
            // 이 부분은 Gemini API의 최신 권장 사항을 따르는 것이 좋음.
            // 일반적으로 system prompt는 첫 번째 user 메시지에 포함되거나,
            // API가 지원하는 경우 별도의 system_instruction 필드로 전달됩니다.
            // 여기서는 첫 번째 user 턴에 포함시키는 로직을 유지하되,
            // history가 있다면, history의 첫 메시지가 user이고, 그것이 system instruction을 포함했다고 가정.
            // 또는, 매번 system instruction을 user 메시지 앞에 붙여서 보낼 수도 있지만, 토큰 낭비가 될 수 있음.
            // 가장 안전한 방법은 systemInstruction을 conversationHistory에는 포함하지 않고,
            // 여기서 API 요청을 만들 때 항상 conversationHistory 앞에 추가하는 것.

            // 여기서는 conversationHistory에 execution_feedback을 "user" 역할로 변환하여 추가
            List<Part> systemParts = new ArrayList<>();
            systemParts.add(new Part(systemInstruction)); // 시스템 지침은 항상 먼저
            geminiApiContents.add(new com.example.ai_macrofy.llm.gemini.data.Message(systemParts, "user"));
            // 모델이 응답하도록 빈 모델 메시지 추가 (옵션, API 동작에 따라)
            // List<Part> emptyModelParts = new ArrayList<>();
            // emptyModelParts.add(new Part("")); // 또는 응답을 기다린다는 표시
            // geminiApiContents.add(new com.example.ai_macrofy.llm.gemini.data.Message(emptyModelParts, "model"));


            for (com.example.ai_macrofy.llm.common.ChatMessage histMsg : conversationHistory) {
                List<Part> histParts = new ArrayList<>();
                String content = histMsg.content;
                String geminiRole;

                if ("execution_feedback".equals(histMsg.role)) {
                    // Gemini는 'execution_feedback' 역할을 직접 지원하지 않음.
                    // 이를 'user' 메시지로 보내고, 내용은 명시적으로 피드백임을 알림.
                    geminiRole = "user";
                    content = "System Execution Feedback:\n" + histMsg.content;
                } else {
                    geminiRole = "assistant".equals(histMsg.role) ? "model" : "user";
                }
                histParts.add(new Part(content));
                geminiApiContents.add(new com.example.ai_macrofy.llm.gemini.data.Message(histParts, geminiRole));
            }
            // Add current user message
            List<Part> currentParts = new ArrayList<>();
            currentParts.add(new Part(currentTurnUserRawContent));
            geminiApiContents.add(new com.example.ai_macrofy.llm.gemini.data.Message(currentParts, "user"));
        }

        Integer thinkingBudget = 32;
        Log.d("GeminiManager", "Final content items for Gemini: " + geminiApiContents.size());
        // Gemini API는 엄격한 user/model 번갈아 나오는 순서를 요구할 수 있음.
        // 위 로직은 system prompt를 첫 user 메시지로 보내고, history를 붙이고, 현재 user 메시지를 붙이는 방식.
        // 필요시 API 문서에 맞춰 메시지 순서 조정.
        callGeminiApi(DEFAULT_MODEL_NAME, geminiApiContents, thinkingBudget, callback);
    }
}