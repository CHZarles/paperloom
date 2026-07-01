package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public record ProductTurnRequest(
        Long userId,
        String conversationId,
        String generationId,
        String userMessage,
        SourceScope lockedScope,
        List<Map<String, String>> history,
        Map<String, Object> memory,
        ProductModelContext modelContext,
        Consumer<ToolProgressEvent> progressListener
) {
    public ProductTurnRequest(Long userId,
                              String conversationId,
                              String generationId,
                              String userMessage,
                              SourceScope lockedScope,
                              List<Map<String, String>> history,
                              Map<String, Object> memory,
                              ProductModelContext modelContext) {
        this(userId, conversationId, generationId, userMessage, lockedScope, history, memory, modelContext, event -> {
        });
    }

    public ProductTurnRequest {
        conversationId = conversationId == null ? "" : conversationId.trim();
        generationId = generationId == null ? "" : generationId.trim();
        userMessage = userMessage == null ? "" : userMessage.trim();
        lockedScope = lockedScope == null ? SourceScope.auto() : lockedScope;
        history = history == null ? List.of() : List.copyOf(history);
        memory = memory == null ? Map.of() : Map.copyOf(memory);
        modelContext = modelContext == null ? ProductModelContext.defaults() : modelContext;
        progressListener = progressListener == null ? event -> {
        } : progressListener;
    }
}
