package com.example.ai_macrofy.llm.gpt;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.llm.gpt.data.ContentPart;
import com.example.ai_macrofy.llm.gpt.data.GPTRequest;
import com.example.ai_macrofy.llm.gpt.data.GPTResponse;
import com.example.ai_macrofy.llm.gpt.data.ImageUrl;
import com.example.ai_macrofy.llm.gpt.data.Message;
import com.google.gson.Gson;

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
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class GPTManager implements AiModelService {
    private static final String BASE_URL = "https://api.openai.com/";
    private String apiKeyInstance;
    private Context context;
    private static final String DEFAULT_MODEL_NAME = "gpt-4o-mini";
    private final Gson gson = new Gson();

    private static volatile Retrofit retrofitInstanceGpt;
    private GPTApi apiInstanceInternalGpt;

    public GPTManager() {}

    private GPTApi getApiForInstance() {
        if (apiInstanceInternalGpt == null) {
            if (retrofitInstanceGpt == null) {
                synchronized (GPTManager.class) {
                    if (retrofitInstanceGpt == null) {
                        retrofitInstanceGpt = new Retrofit.Builder()
                                .baseUrl(BASE_URL)
                                .addConverterFactory(GsonConverterFactory.create())
                                .build();
                    }
                }
            }
            apiInstanceInternalGpt = retrofitInstanceGpt.create(GPTApi.class);
        }
        return apiInstanceInternalGpt;
    }

    @Override
    public void setApiKey(String apiKey) {
        this.apiKeyInstance = apiKey;
    }

    @Override
    public void setContext(Context context) {
        this.context = context;
    }

    @Override
    public void generateResponse(String systemInstruction, List<ChatMessage> conversationHistory, @Nullable String currentScreenLayoutJson, @Nullable Bitmap currentScreenBitmap, @Nullable String currentScreenText, String currentUserVoiceCommand, ModelResponseCallback callback) {
        if (apiKeyInstance == null || apiKeyInstance.isEmpty()) {
            callback.onError("OpenAI API key is not set.");
            return;
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemInstruction));

        for (ChatMessage chatMsg : conversationHistory) {
            String role = "assistant".equals(chatMsg.role) ? "assistant" : "user";
            String content = chatMsg.content;
            if ("execution_feedback".equals(chatMsg.role)) {
                content = "System Execution Feedback:\n" + chatMsg.content;
            }
            messages.add(new Message(role, content));
        }

        String userPrompt = "Current Screen Text:\n" + (currentScreenText != null ? currentScreenText : "Not available.") + "\n\n" +
                "User's Current Command/Question:\n" + processUserCommandForPrompt(currentUserVoiceCommand) + "\n\n" +
                "Note: The provided image includes a 100x100 pixel grid. Use this grid to determine precise coordinates for your actions.";

        List<ContentPart> userContentParts = new ArrayList<>();
        userContentParts.add(new ContentPart("text", userPrompt, null));

        if (currentScreenBitmap != null) {
            Log.d("GPTManager", "Drawing grid on screenshot and encoding for GPT request.");
            Bitmap bitmapWithGrid = drawGridOnBitmap(currentScreenBitmap);

            // (디버깅용) 격자 이미지를 파일로 저장. 필요 없으면 이 라인을 주석 처리.
            // saveBitmapForDebug(bitmapWithGrid);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmapWithGrid.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            byte[] imageBytes = baos.toByteArray();
            String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            ImageUrl imageUrl = new ImageUrl("data:image/jpeg;base64," + base64Image);
            userContentParts.add(new ContentPart("image_url", null, imageUrl));
        } else {
            Log.w("GPTManager", "Bitmap is null, sending request without image.");
        }

        messages.add(new Message("user", userContentParts));

        GPTRequest request = new GPTRequest(DEFAULT_MODEL_NAME, messages);

        // 디버깅을 위해 최종 요청 스크립트를 저장합니다.
        saveFinalScriptForDebug(gson.toJson(request));

        getApiForInstance().createChatCompletion("Bearer " + apiKeyInstance, request).enqueue(new Callback<GPTResponse>() {
            @Override
            public void onResponse(Call<GPTResponse> call, Response<GPTResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseText = response.body().getFirstChoiceMessageContent();
                    if (responseText != null) {
                        callback.onSuccess(responseText);
                    } else {
                        callback.onError("OpenAI response was empty or invalid.");
                    }
                } else {
                    try {
                        String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
                        Log.e("GPTManager", "OpenAI API call failed: " + response.code() + " - " + errorBody);
                        callback.onError("OpenAI API Error: " + response.code() + " " + errorBody);
                    } catch (IOException e) {
                        Log.e("GPTManager", "Error reading error body", e);
                        callback.onError("OpenAI API Error: " + response.code());
                    }
                }
            }

            @Override
            public void onFailure(Call<GPTResponse> call, Throwable t) {
                Log.e("GPTManager", "OpenAI API call onFailure: " + t.getMessage(), t);
                callback.onError("OpenAI Network Error: " + t.getMessage());
            }
        });
    }

    @Override
    public String processUserCommandForPrompt(String userCommand) {
        return userCommand != null ? userCommand.trim() : "";
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

        for (int x = step; x < width; x += step) {
            canvas.drawLine(x, 0, x, height, paint);
        }

        for (int y = step; y < height; y += step) {
            canvas.drawLine(0, y, width, y, paint);
        }

        return mutableBitmap;
    }

    private void saveBitmapForDebug(Bitmap bitmap) {
        if (context == null) {
            Log.e("GPTManager", "Context is null, cannot save debug image.");
            return;
        }
        try {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                Log.e("GPTManager", "External cache directory is not available.");
                return;
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "debug_grid_gpt_" + timeStamp + ".jpg";
            File file = new File(cacheDir, fileName);

            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
            Log.d("GPTManager", "Debug image saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e("GPTManager", "Error saving debug image", e);
        }
    }

    private void saveFinalScriptForDebug(String script) {
        if (context == null) {
            Log.e("GPTManager", "Context is null, cannot save debug script.");
            return;
        }
        try {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                Log.e("GPTManager", "External cache directory is not available.");
                return;
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "debug_script_gpt_" + timeStamp + ".json";
            File file = new File(cacheDir, fileName);

            FileOutputStream out = new FileOutputStream(file);
            out.write(script.getBytes());
            out.flush();
            out.close();
            Log.d("GPTManager", "Debug script saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e("GPTManager", "Error saving debug script", e);
        }
    }
}