package com.yizhaoqi.smartpai.controller.dto;

import java.util.List;
import java.util.Map;

public final class ConversationScopeRequests {
    private ConversationScopeRequests() {
    }

    public record UpdateConversationScopeRequest(
            String scopeMode,
            String sourceLabel,
            List<Long> collectionIds,
            List<String> paperIds,
            Map<String, Object> sourceRecipe
    ) {
    }
}
