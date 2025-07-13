package com.example.ai_macrofy.llm.gemini;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;
import android.util.Log;
import android.content.Context;
import android.os.Environment;

import androidx.annotation.Nullable;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage; // Import common ChatMessage
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
// Gemini data classes
import com.example.ai_macrofy.llm.gemini.data.Choice; //
import com.example.ai_macrofy.llm.gemini.data.GenerationConfig;
import com.example.ai_macrofy.llm.gemini.data.GeminiRequest;
import com.example.ai_macrofy.llm.gemini.data.GeminiResponse;
import com.example.ai_macrofy.llm.gemini.data.InlineData; // Import InlineData
import com.example.ai_macrofy.llm.gemini.data.Message; // This is Gemini's Message
import com.example.ai_macrofy.llm.gemini.data.Part;
import com.example.ai_macrofy.llm.gemini.data.ThinkingConfig;
import com.google.gson.Gson;


import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;


import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class GeminiManager implements AiModelService {
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/";
    private String apiKeyInstance; // Instance-specific API key
    private Context context; // For file operations
    private final Gson gson = new Gson();

    // Model name can be made configurable
    private static final String DEFAULT_MODEL_NAME = "gemini-1.5-flash-latest"; // Updated to the latest model

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

    @Override
    public void setContext(Context context) {
        this.context = context;
    }

    // This internal method now takes the structured list of Gemini Messages
    private void callGeminiApi(String modelName, Message systemInstruction, List<com.example.ai_macrofy.llm.gemini.data.Message> geminiApiContents, @Nullable Integer thinkingBudget, ModelResponseCallback callback) {
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

        GeminiRequest request = new GeminiRequest(geminiApiContents, systemInstruction, generationConfig); //

        // 디버깅을 위해 최종 요청 스크립트를 저장합니다.
        saveFinalScriptForDebug(gson.toJson(request));

        Log.d("GeminiManager", "Sending request to Gemini model: " + modelName + " with " + geminiApiContents.size() + " content items."
                + (generationConfig != null && generationConfig.getThinkingConfig() != null ? " with thinkingBudget: " + generationConfig.getThinkingConfig().getThinkingBudget() : "")); //

        getApiForInstance().generateContent(modelName, apiKeyInstance, request).enqueue(new Callback<GeminiResponse>() { //
            @Override
            public void onResponse(Call<GeminiResponse> call, retrofit2.Response<GeminiResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    GeminiResponse geminiResponse = response.body(); //
                    String responseText = geminiResponse.getFirstCandidateText(); //
                    if (responseText != null) {
                        callback.onSuccess(responseText);
                    } else {
                        callback.onError("Gemini response was empty or invalid.");
                    }
                } else {
                    try {
                        String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                        Log.e("GeminiManager", "Gemini API call failed: " + response.code() + " - " + errorBody);
                        callback.onError("Gemini API Error: " + response.code() + " " + errorBody);
                    } catch (IOException e) {
                        Log.e("GeminiManager", "Error reading error body", e);
                        callback.onError("Gemini API Error: " + response.code());
                    }
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
                                 @Nullable String currentScreenLayoutJson, // Not used
                                 @Nullable Bitmap currentScreenBitmap, // New bitmap parameter
                                 @Nullable String currentScreenText, // New text parameter
                                 String currentUserVoiceCommand,
                                 ModelResponseCallback callback) {

        List<com.example.ai_macrofy.llm.gemini.data.Message> geminiApiContents = new ArrayList<>();

        // 1. Create the dedicated system instruction message
        List<Part> systemParts = new ArrayList<>();
        systemParts.add(new Part(systemInstruction));
        Message systemMessage = new Message(systemParts, "system"); // Role can be arbitrary here, not sent in JSON

        // 2. Populate conversation history
        for (com.example.ai_macrofy.llm.common.ChatMessage histMsg : conversationHistory) {
            List<Part> histParts = new ArrayList<>();
            String content = histMsg.content;
            String geminiRole;

            if ("execution_feedback".equals(histMsg.role)) {
                geminiRole = "user";
                content = "System Execution Feedback:\n" + histMsg.content;
            } else {
                geminiRole = "assistant".equals(histMsg.role) ? "model" : "user";
            }
            histParts.add(new Part(content));
            geminiApiContents.add(new com.example.ai_macrofy.llm.gemini.data.Message(histParts, geminiRole));
        }

        // 3. Add the current user turn content with IMAGE and TEXT
        String currentTurnUserTextContent =
                "Current Screen Text:\n" + (currentScreenText != null ? currentScreenText : "Not available.") + "\n\n" +
                "User's Current Command/Question:\n" + processUserCommandForPrompt(currentUserVoiceCommand) + "\n\n" +
                "Note: The provided image includes a 100x100 pixel grid. Use this grid to determine precise coordinates for your actions.";

        List<Part> currentParts = new ArrayList<>();
        // Add text part first
        currentParts.add(new Part(currentTurnUserTextContent));

        // Add image part if available
        if (currentScreenBitmap != null) {
            Log.d("GeminiManager", "Drawing grid on screenshot and encoding for Gemini request.");

            // 원본 이미지에 격자 그리기
            Bitmap bitmapWithGrid = drawGridOnBitmap(currentScreenBitmap);

            // (디버깅용) 격자 이미지를 파일로 저장. 필요 없으면 이 라인을 주석 처리.
            // saveBitmapForDebug(bitmapWithGrid);

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            // 품질을 낮추기 전 격자가 그려진 비트맵을 압축
            bitmapWithGrid.compress(Bitmap.CompressFormat.JPEG, 85, byteArrayOutputStream);
            byte[] imageBytes = byteArrayOutputStream.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            // 수정된 Part 생성 방식 사용
            InlineData inlineData = new InlineData("image/jpeg", base64Image);
            Part imagePart = new Part(inlineData);
            currentParts.add(imagePart);
        } else {
            Log.e("GeminiManager", "Bitmap is null, sending request without image.");
        }

        geminiApiContents.add(new com.example.ai_macrofy.llm.gemini.data.Message(currentParts, "user"));


        Integer thinkingBudget = 256;
        Log.d("GeminiManager", "Final content items for Gemini: " + geminiApiContents.size());
        callGeminiApi(DEFAULT_MODEL_NAME, systemMessage, geminiApiContents, thinkingBudget, callback);
    }

    @Override
    public void cleanup() {
        // No-op for API-based model
    }

    private Bitmap drawGridOnBitmap(Bitmap originalBitmap) {
        Bitmap mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStrokeWidth(1);
        paint.setStyle(Paint.Style.STROKE);

        int width = originalBitmap.getWidth();
        int height = originalBitmap.getHeight();
        int step = 100;

        // Draw vertical lines
        for (int x = step; x < width; x += step) {
            canvas.drawLine(x, 0, x, height, paint);
        }

        // Draw horizontal lines
        for (int y = step; y < height; y += step) {
            canvas.drawLine(0, y, width, y, paint);
        }

        return mutableBitmap;
    }

    /**
     * 디버깅 목적으로 비트맵을 외부 저장소의 앱 캐시 디렉터리에 저장합니다.
     * 이 경로는 권한 없이 접근 가능합니다.
     * @param bitmap 저장할 비트맵
     */
    private void saveBitmapForDebug(Bitmap bitmap) {
        if (context == null) {
            Log.e("GeminiManager", "Context is null, cannot save debug image.");
            return;
        }
        try {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                Log.e("GeminiManager", "External cache directory is not available.");
                return;
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "debug_grid_" + timeStamp + ".jpg";
            File file = new File(cacheDir, fileName);

            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
            Log.d("GeminiManager", "Debug image saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e("GeminiManager", "Error saving debug image", e);
        }
    }

    private void saveFinalScriptForDebug(String script) {
        if (context == null) {
            Log.e("GeminiManager", "Context is null, cannot save debug script.");
            return;
        }
        try {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                Log.e("GeminiManager", "External cache directory is not available.");
                return;
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "debug_script_gemini_" + timeStamp + ".json";
            File file = new File(cacheDir, fileName);

            FileOutputStream out = new FileOutputStream(file);
            out.write(script.getBytes());
            out.flush();
            out.close();
            Log.d("GeminiManager", "Debug script saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e("GeminiManager", "Error saving debug script", e);
        }
    }
}