package io.github.chzarles.paperloom.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ConversationRetryContext(
        String kind,
        String retryOfGenerationId,
        Long retryOfConversationRecordId,
        Long answerSlotId,
        int targetRevision,
        String reason,
        String previousAnswerMarkdown,
        List<String> previousCitedEvidenceIds,
        String conversationId,
        String question,
        Map<String, Object> effectiveScope
) {
    public ConversationRetryContext {
        kind = kind == null || kind.isBlank() ? "USER_UNSATISFIED" : kind.trim();
        retryOfGenerationId = retryOfGenerationId == null ? "" : retryOfGenerationId.trim();
        reason = reason == null || reason.isBlank() ? "user_requested" : reason.trim();
        previousAnswerMarkdown = previousAnswerMarkdown == null ? "" : previousAnswerMarkdown;
        previousCitedEvidenceIds = previousCitedEvidenceIds == null ? List.of() : List.copyOf(previousCitedEvidenceIds);
        conversationId = conversationId == null ? "" : conversationId.trim();
        question = question == null ? "" : question.trim();
        effectiveScope = effectiveScope == null ? Map.of() : Map.copyOf(effectiveScope);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("kind", kind);
        retry.put("retry_of_generation_id", retryOfGenerationId);
        retry.put("retry_of_conversation_record_id", retryOfConversationRecordId);
        retry.put("answer_slot_id", answerSlotId);
        retry.put("target_revision", targetRevision);
        retry.put("reason", reason);
        retry.put("previous_answer_markdown", previousAnswerMarkdown);
        retry.put("previous_cited_evidence_ids", previousCitedEvidenceIds);
        return retry;
    }

    public Map<String, Object> toClientPayload(String generationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", generationId);
        payload.put("conversationId", conversationId);
        payload.put("retryOfGenerationId", retryOfGenerationId);
        payload.put("retryOfConversationRecordId", retryOfConversationRecordId);
        payload.put("answerSlotId", answerSlotId);
        payload.put("answerRevision", targetRevision);
        payload.put("replaceMessage", true);
        return payload;
    }
}
