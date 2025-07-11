package com.example.ai_macrofy.llm.gemini.data;

import java.util.List;

// PromptFeedback도 포함될 수 있음
public class GeminiResponse {
    private List<Choice> candidates; // OpenAI의 choices 대신 candidates 사용
    // private PromptFeedback promptFeedback;

    public GeminiResponse(List<Choice> candidates) {
        this.candidates = candidates;
    }

    // Gson이 candidates 필드를 채울 수 있도록 getter 필요
    public List<Choice> getCandidates() {
        return candidates;
    }

    // 편의 메서드 (OpenAI 방식과 유사하게 첫 번째 후보의 텍스트 반환)
    public String getFirstCandidateText() {
        if (candidates != null && !candidates.isEmpty()) {
            Choice firstCandidate = candidates.get(0);
            if (firstCandidate != null && firstCandidate.getContent() != null) {
                return firstCandidate.getContent().getFirstPartText();
            }
        }
        return null;
    }
}