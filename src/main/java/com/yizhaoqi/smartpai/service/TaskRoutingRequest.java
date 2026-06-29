package com.yizhaoqi.smartpai.service;

public record TaskRoutingRequest(
        String userId,
        String conversationId,
        String userMessage,
        SourceScope sourceScope
) {
    public TaskRoutingRequest {
        userId = userId == null ? "" : userId.trim();
        conversationId = conversationId == null ? "" : conversationId.trim();
        userMessage = userMessage == null ? "" : userMessage.trim();
        sourceScope = sourceScope == null ? SourceScope.auto() : sourceScope;
    }
}
