package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.PaperLocationType;

import java.util.List;

public record ReadingModelGrepSearchRequest(
        List<String> paperIds,
        String queryText,
        List<PaperLocationType> locationTypes,
        Integer pageFrom,
        Integer pageTo,
        int limit
) {
    public static final int DEFAULT_LOCATION_LIMIT = 60;
    public static final int MAX_LOCATION_LIMIT = 200;

    public ReadingModelGrepSearchRequest {
        paperIds = paperIds == null
                ? List.of()
                : paperIds.stream()
                        .filter(paperId -> paperId != null && !paperId.isBlank())
                        .distinct()
                        .toList();
        queryText = queryText == null ? "" : queryText.trim();
        locationTypes = locationTypes == null ? List.of() : List.copyOf(locationTypes);
        if (pageFrom != null && pageTo != null && pageFrom > pageTo) {
            Integer originalPageFrom = pageFrom;
            pageFrom = pageTo;
            pageTo = originalPageFrom;
        }
        limit = clampLimit(limit);
    }

    public static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit < 1) {
            return DEFAULT_LOCATION_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LOCATION_LIMIT);
    }
}
