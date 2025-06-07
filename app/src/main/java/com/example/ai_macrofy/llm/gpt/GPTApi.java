package com.example.ai_macrofy.llm.gpt;

import com.example.ai_macrofy.llm.gpt.data.GPTResponse;
import com.example.ai_macrofy.llm.gpt.data.GPTRequest;
import com.example.ai_macrofy.llm.gpt.data.GPTRequest;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface GPTApi {
    @POST("v1/chat/completions")
    Call<GPTResponse> getResponse(
            @Header("Authorization") String authorization,
            @Body GPTRequest request
    );
}