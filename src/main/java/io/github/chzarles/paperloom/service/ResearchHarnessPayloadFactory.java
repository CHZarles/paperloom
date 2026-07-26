package io.github.chzarles.paperloom.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ResearchHarnessPayloadFactory {

    public Map<String, Object> requestBody(ProductTurnRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", request.generationId());
        body.put("conversation_id", request.conversationId());
        body.put("user_id", request.userId());
        body.put("user_message", request.userMessage());
        body.put("history", request.history());
        body.put("scope", Map.of(
                "mode", request.lockedScope().mode().name(),
                "paper_ids", request.lockedScope().paperIds(),
                "reference_focus", request.memory()
        ));
        body.put("research_memory", researchMemory(request.memory()));
        body.put("options", Map.of(
                "include_trace", true,
                "max_completion_tokens", request.modelContext().maxCompletionTokens()
        ));
        body.put("retry", request.retryContext().isEmpty() ? null : request.retryContext());
        return body;
    }

    public int estimatedPromptTokens(ProductTurnRequest request) {
        int characters = request.userMessage().length();
        for (Map<String, String> message : request.history()) {
            characters += message.getOrDefault("role", "").length();
            characters += message.getOrDefault("content", "").length();
        }
        return Math.max(1, (characters + 3) / 4);
    }

    private Map<String, Object> researchMemory(Map<String, Object> memory) {
        if (memory == null || memory.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        copy(memory, result, "selected_paper_ids");
        copy(memory, result, "selected_evidence_ids");
        copy(memory, result, "previous_evidence");
        return result;
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }
}
