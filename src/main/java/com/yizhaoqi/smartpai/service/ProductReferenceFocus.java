package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.regex.Pattern;

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
        String sourceKind,
        String sourceQuoteRef
) {
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");

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
        sourceQuoteRef = sourceQuoteRef(sourceQuoteRef);
    }

    public ProductReferenceFocus(List<String> paperIds,
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
                                 String sourceKind) {
        this(
                paperIds,
                paperTitles,
                referenceNumber,
                conversationRecordId,
                chunkId,
                pageNumber,
                paperId,
                paperTitle,
                originalFilename,
                matchedText,
                bboxJson,
                sourceKind,
                null
        );
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sourceQuoteRef(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null || !SOURCE_QUOTE_REF_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }
}
