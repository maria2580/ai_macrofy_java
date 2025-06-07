package com.example.ai_macrofy.llm.gemini.data;

// Gemini API의 Candidate 구조에 해당
public class Choice { // 이름을 CandidateRepresentation 또는 GeminiCandidate 등으로 변경 고려
    private Message content; // OpenAI의 message 대신 content 사용
    private String finishReason;
    // private List<SafetyRating> safetyRatings;
    // private CitationMetadata citationMetadata;
    // private int index; // 필요시

    public Choice(Message content) {
        this.content = content;
    }

    public Message getContent() {
        return content;
    }

    public String getFinishReason() { // 실제 응답 파싱 시 이 필드도 채워야 함
        return finishReason;
    }

    // safetyRatings 등의 getter 추가 가능
}