package io.github.chzarles.paperloom.service;

import java.util.List;

public record PaperCandidate(
        String paperId,
        String title,
        String authors,
        Integer publicationYear,
        String venue,
        String abstractPreview,
        List<String> matchedFields,
        String matchReason,
        int rank
) {
    public PaperCandidate {
        matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
    }
}
