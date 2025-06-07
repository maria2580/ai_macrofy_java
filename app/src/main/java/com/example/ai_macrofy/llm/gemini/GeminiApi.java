package com.example.ai_macrofy.llm.gemini;

import com.example.ai_macrofy.llm.gemini.data.GeminiRequest;
import com.example.ai_macrofy.llm.gemini.data.GeminiResponse;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface GeminiApi {
    // 예시 엔드포인트 및 모델 이름. 실제 사용하는 모델에 맞게 수정 필요.
    // URL: "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent?key=YOUR_API_KEY"
    @POST("v1beta/models/{modelName}:generateContent")
    Call<GeminiResponse> generateContent(
            @Path("modelName") String modelName, // 경로 파라미터로 모델 이름 전달
            @Query("key") String apiKey,         // 쿼리 파라미터로 API 키 전달
            @Body GeminiRequest request
    );
}