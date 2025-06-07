package com.example.ai_macrofy.llm.gpt;

import android.util.Log;

import androidx.annotation.Nullable;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage; // Import common ChatMessage
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.llm.gpt.data.Choice;
import com.example.ai_macrofy.llm.gpt.data.GPTRequest;
import com.example.ai_macrofy.llm.gpt.data.GPTResponse;
import com.example.ai_macrofy.llm.gpt.data.Message; // This is GPT's Message

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList; // Import ArrayList
import java.util.Collections;
import java.util.List; // Import List

import retrofit2.Call;
import retrofit2.Callback;
// import retrofit2.HttpException; // Not explicitly used here, but good for context
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class GPTManager implements AiModelService {
    private static final String BASE_URL = "https://api.openai.com/";
    private String apiKeyInstance; // Use instance-specific API key

    private static volatile Retrofit retrofitInstance; // Keep Retrofit instance potentially shared if base URL is same
    private GPTApi apiInstanceInternal; // Instance specific API

    public GPTManager() {
        // Constructor can be used for one-time setup if needed
    }

    private GPTApi getApiForInstance() {
        if (apiInstanceInternal == null) {
            // Create a new Retrofit instance or reuse a shared one if appropriate
            // For simplicity, creating a new one each time getApiForInstance is called if null
            // but ideally, Retrofit instance is created once.
            // Let's ensure Retrofit is created once, but API interface is per manager instance if needed,
            // or also shared if no instance-specific state in it.
            // Assuming GPTApi can be shared if Retrofit is shared.
            if (retrofitInstance == null) {
                synchronized (GPTManager.class) {
                    if (retrofitInstance == null) {
                        retrofitInstance = new Retrofit.Builder()
                                .baseUrl(BASE_URL)
                                .addConverterFactory(GsonConverterFactory.create())
                                .build();
                    }
                }
            }
            apiInstanceInternal = retrofitInstance.create(GPTApi.class);
        }
        return apiInstanceInternal;
    }


    @Override
    public void setApiKey(String apiKey) {
        this.apiKeyInstance = apiKey;
    }

    // Renamed from getResponseFromGPTInternal and adapted
    private void callGptApi(List<Message> gptApiMessages, ModelResponseCallback callback) {
        if (apiKeyInstance == null || apiKeyInstance.isEmpty()) {
            callback.onError("GPT API key is not set for this instance");
            return;
        }

        GPTRequest request = new GPTRequest(
                "gpt-4o-mini", // Or your desired model
                gptApiMessages
        );

        getApiForInstance().getResponse("Bearer " + apiKeyInstance, request).enqueue(new Callback<GPTResponse>() {
            @Override
            public void onResponse(Call<GPTResponse> call, retrofit2.Response<GPTResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    GPTResponse gptResponse = response.body();
                    if (gptResponse.getChoices() != null && !gptResponse.getChoices().isEmpty()) {
                        Choice firstChoice = gptResponse.getChoices().get(0);
                        if (firstChoice != null && firstChoice.getMessage() != null && firstChoice.getMessage().getContent() != null) {
                            callback.onSuccess(firstChoice.getMessage().getContent());
                        } else {
                            callback.onSuccess("No valid message content in response from GPT.");
                        }
                    } else {
                        callback.onSuccess("No response choices received from GPT.");
                    }
                } else {
                    String errorBodyString = "Unknown error";
                    if (response.errorBody() != null) {
                        try {
                            errorBodyString = response.errorBody().string();
                        } catch (IOException e) {
                            Log.e("GPTManager", "Error reading error body", e);
                        }
                    }
                    callback.onError("GPT Error: " + response.message() + " (Code: " + response.code() + ") Body: " + errorBodyString);
                }
            }

            @Override
            public void onFailure(Call<GPTResponse> call, Throwable t) {
                Log.e("GPTManager", "GPT API call onFailure: " + t.getMessage(), t);
                callback.onError("GPT Network Error: " + t.getMessage());
            }
        });
    }

    public static String processGptInstructionsContent(String input) { //
        if (input == null) return "";
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public String processUserCommandForPrompt(String userCommand) { //
        String escapedInput = processGptInstructionsContent(userCommand);
        return "{\n    \"instructions\": \"" + escapedInput + "\"\n}";
    }


    @Override
    public void generateResponse(String systemInstruction,
                                 List<ChatMessage> conversationHistory,
                                 String currentScreenLayoutJson,
                                 String currentUserVoiceCommand,
                                 String previousActionContext,
                                 ModelResponseCallback callback) {

        List<com.example.ai_macrofy.llm.gpt.data.Message> gptApiMessages = new ArrayList<>();

        // 1. Add system message
        gptApiMessages.add(new com.example.ai_macrofy.llm.gpt.data.Message("system", systemInstruction));

        // 2. Add conversation history (including execution_feedback)
        for (com.example.ai_macrofy.llm.common.ChatMessage histMsg : conversationHistory) {
            String role = histMsg.role;
            // GPT는 'user', 'assistant', 'system', 'tool' 등의 역할을 사용합니다.
            // 'execution_feedback'을 'user' 또는 'system' 메시지로 전달할 수 있습니다.
            // 여기서는 LLM이 사용자 입력의 일부로 자연스럽게 보도록 'user'로 전달하거나,
            // 또는 시스템 지침과 유사하게 'system' 메시지로 전달할 수 있습니다.
            // 프롬프트에서 'execution_feedback'을 대화의 일부로 언급했으므로,
            // 맥락상 LLM이 처리해야 할 정보로써 'user' 역할 뒤에 오는 것이 자연스러울 수 있습니다.
            // 또는, LLM이 명확히 구분하도록 특별한 지시와 함께 전달합니다.
            // 여기서는 'execution_feedback'을 'assistant'가 아닌, 다음 'user' 턴에 대한 정보로 간주,
            // 또는 LLM이 이전 'assistant'의 행동에 대한 결과로 보도록 'user' 메시지로 전달
            // (만약 LLM이 'execution_feedback'이라는 역할을 직접 지원하지 않는다면).
            // 가장 간단하게는, 프롬프트에서 `execution_feedback` (with role 'execution_feedback') 라고 했으므로,
            // 이 역할을 그대로 사용하거나, GPT가 이해할 수 있는 역할(예: user, system)로 매핑합니다.
            // 여기서는 'execution_feedback' 내용을 사용자 메시지 앞에 붙이거나,
            // 별도 메시지로 구분하지 않고 내용에 포함시킵니다.
            // 현재 MyForegroundService는 "execution_feedback" 이라는 role로 ChatMessage를 만듭니다.
            // GPT는 이 role을 직접 이해하지 못할 수 있으므로, "user" 또는 "system"으로 매핑하거나,
            // "user" 메시지의 content에 "Execution Feedback: ..." 형태로 포함합니다.
            // 여기서는 "user" 역할로 보내고, 내용은 "Execution Feedback: [내용]" 형태로 보냅니다.

            if ("execution_feedback".equals(histMsg.role)) {
                // GPT는 'execution_feedback' 역할을 직접 지원하지 않으므로, 'user' 역할로 보내고 내용을 명시
                gptApiMessages.add(new com.example.ai_macrofy.llm.gpt.data.Message("user", "System Execution Feedback:\n" + histMsg.content));
            } else {
                String gptRole = "assistant".equals(histMsg.role) || "model".equals(histMsg.role) ? "assistant" : "user";
                gptApiMessages.add(new com.example.ai_macrofy.llm.gpt.data.Message(gptRole, histMsg.content));
            }
        }

        // 3. Construct and add current user message
        String gptFormattedUserVoiceCommandJson = processUserCommandForPrompt(currentUserVoiceCommand);

        String currentUserContentForGpt =
                "User's direct command context (JSON formatted for GPT):\n" +
                        gptFormattedUserVoiceCommandJson + "\n\n" +
                        "Layout Analysis Context:\n" +
                        "Previous interactions (layout hash, action pairs for repetition check): " + previousActionContext + "\n" +
                        "Current layout hash: " + String.valueOf(currentScreenLayoutJson.length()) + "\n" +
                        "Current layout JSON:\n" + currentScreenLayoutJson;

        gptApiMessages.add(new com.example.ai_macrofy.llm.gpt.data.Message("user", currentUserContentForGpt));

        Log.d("GPTManager", "Sending " + gptApiMessages.size() + " messages to GPT. Last user content starts with: " + currentUserContentForGpt.substring(0, Math.min(currentUserContentForGpt.length(), 100)) + "...");
        callGptApi(gptApiMessages, callback);

    }
}