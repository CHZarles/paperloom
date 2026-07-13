package io.github.chzarles.paperloom.service;

public record PaperCandidateSearchRequest(
        String queryText,
        String userId,
        String orgTags,
        int limit
) {
    public static final int DEFAULT_PAPER_LIMIT = 20;
    public static final int MAX_PAPER_LIMIT = 100;

    public PaperCandidateSearchRequest {
        queryText = queryText == null ? "" : queryText.trim();
        userId = userId == null ? "" : userId.trim();
        orgTags = orgTags == null ? "" : orgTags.trim();
        limit = clampLimit(limit);
    }

    public static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit < 1) {
            return DEFAULT_PAPER_LIMIT;
        }
        return Math.min(requestedLimit, MAX_PAPER_LIMIT);
    }
}
