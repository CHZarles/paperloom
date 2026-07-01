package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.Map;

public record TaskRoutingRequest(
        String userId,
        String conversationId,
        String userMessage,
        SourceScope sourceScope,
        List<Map<String, String>> history
) {
    public TaskRoutingRequest(String userId,
                              String conversationId,
                              String userMessage,
                              SourceScope sourceScope) {
        this(userId, conversationId, userMessage, sourceScope, List.of());
    }

    public TaskRoutingRequest {
        userId = userId == null ? "" : userId.trim();
        conversationId = conversationId == null ? "" : conversationId.trim();
        userMessage = userMessage == null ? "" : userMessage.trim();
        sourceScope = sourceScope == null ? SourceScope.auto() : sourceScope;
        history = history == null ? List.of() : history.stream()
                .filter(message -> message != null
                        && message.get("role") != null
                        && message.get("content") != null
                        && !message.get("content").isBlank())
                .toList();
    }
}
