package com.yizhaoqi.smartpai.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        String sourceQuoteRef,
        List<String> paperHandles,
        String paperHandle,
        String readingAction
) {
    private static final Set<String> READING_ACTIONS = Set.of(
            "SEARCH_PAPERS",
            "LIST_LOCATIONS",
            "FIND_LOCATIONS"
    );
    private static final Pattern SOURCE_QUOTE_REF_PATTERN =
            Pattern.compile("^source_quote_[A-Za-z0-9_-]+$");
    private static final Pattern PAPER_HANDLE_PATTERN =
            Pattern.compile("^paper_handle_[A-Za-z0-9_-]+$");

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
        paperHandle = paperHandle(paperHandle);
        readingAction = readingAction(readingAction);
        paperHandles = paperHandles == null ? List.of() : paperHandles.stream()
                .map(ProductReferenceFocus::paperHandle)
                .filter(value -> value != null)
                .distinct()
                .toList();
        if (paperHandle != null) {
            paperHandles = List.of(paperHandle);
        }
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
                                 String sourceKind,
                                 String sourceQuoteRef) {
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
                sourceQuoteRef,
                List.of(),
                null,
                null
        );
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
                null,
                List.of(),
                null,
                null
        );
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
                                 String sourceKind,
                                 String sourceQuoteRef,
                                 List<String> paperHandles,
                                 String paperHandle) {
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
                sourceQuoteRef,
                paperHandles,
                paperHandle,
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

    private static String paperHandle(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null || !PAPER_HANDLE_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    private static String readingAction(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        return READING_ACTIONS.contains(normalized) ? normalized : null;
    }
}
