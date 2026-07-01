package com.yizhaoqi.smartpai.service;

import java.util.List;

public record ProductReferenceFocus(
        List<String> paperIds,
        List<String> paperTitles,
        Integer referenceNumber,
        Long conversationRecordId,
        Integer chunkId,
        Integer pageNumber,
        String paperId,
        String paperTitle,
        String originalFilename,
        String matchedText,
        String bboxJson,
        String sourceKind
) {
    public ProductReferenceFocus {
        paperIds = paperIds == null ? List.of() : paperIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        paperTitles = paperTitles == null ? List.of() : paperTitles.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        paperId = trimToNull(paperId);
        paperTitle = trimToNull(paperTitle);
        originalFilename = trimToNull(originalFilename);
        matchedText = trimToNull(matchedText);
        bboxJson = trimToNull(bboxJson);
        sourceKind = trimToNull(sourceKind);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
