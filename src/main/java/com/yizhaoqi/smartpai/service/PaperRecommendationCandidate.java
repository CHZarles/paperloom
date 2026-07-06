package com.yizhaoqi.smartpai.service;

import java.util.List;

public record PaperRecommendationCandidate(
        String paperId,
        String title,
        String authors,
        Integer publicationYear,
        String venue,
        String abstractPreview,
        String matchReason,
        String evidenceStatus,
        List<ReadingLocationCandidate> supportingLocations
) {
    public PaperRecommendationCandidate {
        supportingLocations = supportingLocations == null ? List.of() : List.copyOf(supportingLocations);
    }
}
