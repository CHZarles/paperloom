package io.github.chzarles.paperloom.controller.dto;

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
            Map<String, Object> sourceRecipe,
            String titleQuery,
            String titleRegex
    ) {
        public UpdateConversationScopeRequest(String scopeMode,
                                              String sourceLabel,
                                              List<Long> collectionIds,
                                              List<String> paperIds,
                                              Map<String, Object> sourceRecipe) {
            this(scopeMode, sourceLabel, collectionIds, paperIds, sourceRecipe, null, null);
        }
    }
}
