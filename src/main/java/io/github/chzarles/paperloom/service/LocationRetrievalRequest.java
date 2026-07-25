package io.github.chzarles.paperloom.service;

import java.util.Map;
import java.util.Set;

public record LocationRetrievalRequest(Map<String, String> activeModels,
                                        String queryText,
                                        String sectionQuery,
                                        Set<String> elementTypeHints,
                                        Integer pageFrom,
                                        Integer pageTo,
                                        int topK) {
    public LocationRetrievalRequest {
        activeModels = activeModels == null ? Map.of() : Map.copyOf(activeModels);
        queryText = queryText == null ? "" : queryText.trim();
        sectionQuery = sectionQuery == null ? "" : sectionQuery.trim();
        elementTypeHints = elementTypeHints == null ? Set.of() : Set.copyOf(elementTypeHints);
    }
}
