package com.yizhaoqi.smartpai.service;

public record ProductToolContext(
        Long userId,
        String conversationId,
        String generationId,
        SourceScope lockedScope
) {
    public ProductToolContext {
        conversationId = conversationId == null ? "" : conversationId.trim();
        generationId = generationId == null ? "" : generationId.trim();
        lockedScope = lockedScope == null ? SourceScope.auto() : lockedScope;
    }
}
