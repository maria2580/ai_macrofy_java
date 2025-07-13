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
import com.example.ai_macrofy.utils.AppPreferences;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession;
import com.google.mediapipe.tasks.genai.llminference.GraphOptions;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class GemmaManager implements AiModelService {
    private static final String TAG = "GemmaManager";
    public static final String MODEL_FILENAME = "gemma-3n-E4B-it-int4.task";
    private static GemmaManager instance;
    private Context context;
    private LlmInference llmInference;
    // 요청 처리(Hot Path)와 세션 생성(Cold Path)을 위한 실행기를 분리하여 병목 현상 방지
    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService sessionCreationExecutor = Executors.newSingleThreadExecutor();
    // 예비 세션을 저장하기 위한 스레드 안전 큐
    private final BlockingQueue<LlmInferenceSession> spareSessionQueue = new LinkedBlockingQueue<>(3);

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

        sessionCreationExecutor.execute(() -> { // 세션 생성 실행기 사용
            try {
                if (!isModelAvailable(context)) {
                    throw new IOException("Gemma model file not found.");
                }
                File modelFile = new File(context.getFilesDir(), MODEL_FILENAME);

                AppPreferences appPreferences = new AppPreferences(context);
                // Delegate 대신 Backend를 사용하여 실행 장치를 설정합니다.
                Backend backend = AppPreferences.DELEGATE_GPU.equals(appPreferences.getGemmaDelegate())
                        ? Backend.GPU
                        : Backend.CPU;

                Log.d(TAG, "Initializing Gemma with backend: " + backend.name());

                LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.getAbsolutePath())
                        .setMaxTokens(2000) // 지원되는 최대 캐시 크기인 4096으로 설정
                        .setMaxNumImages(1) // 멀티모달 지원을 위해 이미지 수 설정 (주석 해제)
                        .setPreferredBackend(backend) // setDelegate 대신 setPreferredBackend 사용
                        .build();
                llmInference = LlmInference.createFromOptions(context, options);

                // 초기화 성공 후, 첫 예비 세션 준비를 시작합니다.
                // 이제 이 메서드가 연쇄적으로 큐를 채웁니다.
                Log.d(TAG, "LlmInference initialized. Kicking off spare session preparation.");
                prepareSpareSession();

                callback.onInitSuccess();
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize LlmInference", e);
                callback.onInitFailure(e.getMessage());
            }
        });
    }

    /**
     * 다음 요청을 위해 백그라운드에서 새로운 LlmInferenceSession을 생성하여 큐에 추가합니다.
     * 세션 생성 후 큐가 가득 차지 않았다면, 연쇄적으로 다음 세션 생성을 예약합니다.
     */
    private void prepareSpareSession() {
        sessionCreationExecutor.execute(() -> { // 세션 생성 실행기 사용
            // 큐가 이미 가득 찼으면 불필요한 생성을 건너뜁니다.
            if (spareSessionQueue.remainingCapacity() == 0) {
                Log.d(TAG, "Session queue is already full. Skipping creation of a new spare session.");
                return;
            }
            try {
                Log.d(TAG, "Started creating a new spare session... Queue capacity remaining: " + spareSessionQueue.remainingCapacity());
                LlmInferenceSession.LlmInferenceSessionOptions sessionOptions =
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build()) // false를 true로 수정
                                .build();
                LlmInferenceSession session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions);
                spareSessionQueue.put(session); // 큐에 공간이 생길 때까지 대기하며 세션을 추가합니다.
                Log.d(TAG, "New spare session is ready. Queue size: " + spareSessionQueue.size());

                // 세션 추가 후에도 큐에 여유가 있으면 다음 세션 생성을 계속 진행합니다.
                if (spareSessionQueue.remainingCapacity() > 0) {
                    Log.d(TAG, "Queue not full yet. Triggering next spare session creation.");
                    prepareSpareSession();
                }

            } catch (Exception e) {
                Log.e(TAG, "Failed to create a spare session", e);
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

        requestExecutor.execute(() -> { // 요청 처리 실행기 사용
            LlmInferenceSession session = null;
            try {
                // 1. 큐에서 예비 세션을 가져옵니다. 큐가 비어있으면 세션이 준비될 때까지 대기합니다.
                Log.d(TAG, "Waiting to take a spare session from the queue... Current size: " + spareSessionQueue.size());
                session = spareSessionQueue.take();
                Log.d(TAG, "Took a session from the queue. Queue size is now: " + spareSessionQueue.size());

                // 2. 즉시 다음 요청을 위한 예비 세션 생성을 시작합니다.
                prepareSpareSession();

                // 3. 가져온 세션을 사용하여 요청을 처리합니다.
                session.addQueryChunk(textPrompt);

                // 이미지 전송 기능 활성화
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
                
                Log.d("GemmaManager", "Sending request to Gemma...");
                String result = session.generateResponse();
                callback.onSuccess(result);
            } catch (Exception e) {
                Log.e(TAG, "Error generating response from Gemma", e);
                callback.onError(e.getMessage());
            } finally {
                // 4. 사용이 끝난 세션은 폐기합니다.
                if (session != null) {
                    session.close();
                    Log.d(TAG, "Used session has been closed.");
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

    @Override
    public void cleanup() {
        // No-op for local model
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