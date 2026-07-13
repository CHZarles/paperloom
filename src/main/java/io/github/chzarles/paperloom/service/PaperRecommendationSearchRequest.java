package io.github.chzarles.paperloom.service;

public record PaperRecommendationSearchRequest(
        String queryText,
        String userId,
        String orgTags,
        int paperLimit,
        int perPaperLocationLimit
) {
    public static final int DEFAULT_PER_PAPER_LOCATION_LIMIT = 3;
    public static final int MAX_PER_PAPER_LOCATION_LIMIT = 10;

    public PaperRecommendationSearchRequest {
        queryText = queryText == null ? "" : queryText.trim();
        userId = userId == null ? "" : userId.trim();
        orgTags = orgTags == null ? "" : orgTags.trim();
        paperLimit = PaperCandidateSearchRequest.clampLimit(paperLimit);
        perPaperLocationLimit = clampPerPaperLocationLimit(perPaperLocationLimit);
    }

    public static int clampPerPaperLocationLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit < 1) {
            return DEFAULT_PER_PAPER_LOCATION_LIMIT;
        }
        return Math.min(requestedLimit, MAX_PER_PAPER_LOCATION_LIMIT);
    }
}
