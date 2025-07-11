package com.example.ai_macrofy.llm.gemma;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.ai_macrofy.llm.common.AiModelService;
import com.example.ai_macrofy.llm.common.ChatMessage;
import com.example.ai_macrofy.llm.common.ModelResponseCallback;
import com.example.ai_macrofy.services.accessibility.LayoutAccessibilityService;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;
import com.google.mediapipe.tasks.genai.llminference.GraphOptions;


import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GemmaManager implements AiModelService {
    private static final String TAG = "GemmaManager";
    public static final String MODEL_FILENAME = "gemma-3n-E4B-it-int4.task";
    private static GemmaManager instance;
    private Context context;
    private LlmInference llmInference;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private GemmaManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized GemmaManager getInstance(Context context) {
        if (instance == null) {
            instance = new GemmaManager(context);
        }
        return instance;
    }

    public static boolean isModelAvailable(Context context) {
        File modelFile = new File(context.getFilesDir(), MODEL_FILENAME);
        return modelFile.exists();
    }

    public void prepare(InitializationCallback callback) {
        if (llmInference != null) {
            callback.onInitSuccess();
            return;
        }

        backgroundExecutor.execute(() -> {
            try {
                if (!isModelAvailable(context)) {
                    throw new IOException("Gemma model file not found.");
                }
                File modelFile = new File(context.getFilesDir(), MODEL_FILENAME);
                LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.getAbsolutePath())
                        .setMaxTokens(4096)
                        .setMaxNumImages(1) // 멀티모달 지원을 위해 이미지 수 설정
                        .build();
                llmInference = LlmInference.createFromOptions(context, options);
                callback.onInitSuccess();
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize LlmInference", e);
                callback.onInitFailure(e.getMessage());
            }
        });
    }

    @Override
    public void setApiKey(String apiKey) {
        // Not needed for local Gemma
    }

    @Override
    public void setContext(Context context) {
        this.context = context;
    }

    @Override
    public void generateResponse(String systemInstruction, List<ChatMessage> conversationHistory, @Nullable String currentScreenLayoutJson, @Nullable Bitmap currentScreenBitmap, @Nullable String currentScreenText, String currentUserVoiceCommand, ModelResponseCallback callback) {
        if (llmInference == null) {
            callback.onError("Gemma model is not initialized.");
            return;
        }

        // Gemma가 이제 이미지를 지원하므로, 텍스트 프롬프트와 이미지를 함께 사용합니다.
        String textPrompt = buildFinalPrompt(systemInstruction, conversationHistory, currentUserVoiceCommand);

        // 디버깅을 위해 텍스트 프롬프트를 저장합니다.
        saveFinalScriptForDebug(textPrompt);

        backgroundExecutor.execute(() -> {
            LlmInferenceSession.LlmInferenceSessionOptions sessionOptions =
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                            .build();

            LlmInferenceSession session = null;
            try {
                session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions);
                session.addQueryChunk(textPrompt);

                if (currentScreenBitmap != null) {
                    Log.d(TAG, "Drawing grid on screenshot for Gemma request.");
                    Bitmap bitmapWithGrid = drawGridOnBitmap(currentScreenBitmap);

                    // (디버깅용) 격자 이미지를 파일로 저장. 필요 없으면 이 라인을 주석 처리.
                    // saveBitmapForDebug(bitmapWithGrid);

                    // 올바른 BitmapImageBuilder를 사용하여 MPImage 객체 생성
                    MPImage mpImage = new BitmapImageBuilder(bitmapWithGrid).build();
                    session.addImage(mpImage);
                } else {
                    Log.w(TAG, "Bitmap is null, sending request without image to Gemma.");
                }

                String result = session.generateResponse();
                callback.onSuccess(result);
            } catch (Exception e) {
                Log.e(TAG, "Error generating response from Gemma", e);
                callback.onError(e.getMessage());
            } finally {
                if (session != null) {
                    session.close();
                }
            }
        });
    }

    private String buildFinalPrompt(String systemInstruction, List<ChatMessage> conversationHistory, String currentUserVoiceCommand) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(systemInstruction).append("\n\n");

        for (ChatMessage message : conversationHistory) {
            String role = "assistant".equals(message.role) ? "model" : "user";
            if ("execution_feedback".equals(message.role)) {
                promptBuilder.append("user: System Execution Feedback:\n").append(message.content).append("\n");
            } else {
                promptBuilder.append(role).append(": ").append(message.content).append("\n");
            }
        }

        // 사용자 명령을 추가합니다. 이미지는 별도로 전달됩니다.
        String userPrompt = "User's Current Command/Question:\n" + processUserCommandForPrompt(currentUserVoiceCommand) + "\n\n" +
                "Note: The provided image includes a 100x100 pixel grid. Use this grid to determine precise coordinates for your actions.";

        promptBuilder.append("user: ").append(userPrompt).append("\n");
        promptBuilder.append("model:"); // 모델이 응답을 시작하도록 유도합니다.

        return promptBuilder.toString();
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

    private void saveBitmapForDebug(Bitmap bitmap) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot save debug image.");
            return;
        }
        try {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                Log.e(TAG, "External cache directory is not available.");
                return;
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "debug_grid_gemma_" + timeStamp + ".jpg";
            File file = new File(cacheDir, fileName);

            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
            out.close();
            Log.d(TAG, "Debug image saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error saving debug image", e);
        }
    }

    private void saveFinalScriptForDebug(String script) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot save debug script.");
            return;
        }
        try {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                Log.e(TAG, "External cache directory is not available.");
                return;
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "debug_script_gemma_" + timeStamp + ".txt";
            File file = new File(cacheDir, fileName);

            FileOutputStream out = new FileOutputStream(file);
            out.write(script.getBytes());
            out.flush();
            out.close();
            Log.d(TAG, "Debug script saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Error saving debug script", e);
        }
    }
}