package com.yizhaoqi.smartpai.service;

import java.util.List;

public record EvidenceItem(
        String evidenceId,
        String paperId,
        String paperTitle,
        String originalFilename,
        Integer pageNumber,
        Integer chunkId,
        String sourceKind,
        String sectionTitle,
        String matchedText,
        String bboxJson,
        Double score,
        String sourceType,
        String evidenceAssetLevel,
        Boolean pdfEvidenceAvailable,
        Boolean structuredImport,
        Boolean evalImport,
        Boolean pageScreenshotAvailable,
        Boolean figureScreenshotAvailable,
        List<String> assetWarnings,
        String tableId,
        String figureId,
        String formulaId,
        String evidenceRole,
        String tableText,
        String tableMarkdown,
        Boolean tableScreenshotAvailable
) {
    public EvidenceItem {
        sourceKind = sourceKind == null || sourceKind.isBlank() ? "TEXT" : sourceKind;
        sourceType = sourceType == null || sourceType.isBlank() ? "PDF" : sourceType;
        structuredImport = Boolean.TRUE.equals(structuredImport);
        evalImport = Boolean.TRUE.equals(evalImport);
        pdfEvidenceAvailable = Boolean.TRUE.equals(pdfEvidenceAvailable);
        pageScreenshotAvailable = Boolean.TRUE.equals(pageScreenshotAvailable);
        figureScreenshotAvailable = Boolean.TRUE.equals(figureScreenshotAvailable);
        tableScreenshotAvailable = Boolean.TRUE.equals(tableScreenshotAvailable);
        evidenceRole = evidenceRole == null || evidenceRole.isBlank() ? "NORMAL_TEXT" : evidenceRole;
        evidenceAssetLevel = evidenceAssetLevel == null || evidenceAssetLevel.isBlank()
                ? (structuredImport ? "TEXT_ONLY" : pdfEvidenceAvailable ? "PDF_VISUAL" : "PDF_PENDING_ASSETS")
                : evidenceAssetLevel;
        assetWarnings = assetWarnings == null ? List.of() : assetWarnings.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    public EvidenceItem(String evidenceId,
                        String paperId,
                        String paperTitle,
                        String originalFilename,
                        Integer pageNumber,
                        Integer chunkId,
                        String sourceKind,
                        String sectionTitle,
                        String matchedText,
                        String bboxJson,
                        Double score) {
        this(
                evidenceId,
                paperId,
                paperTitle,
                originalFilename,
                pageNumber,
                chunkId,
                sourceKind,
                sectionTitle,
                matchedText,
                bboxJson,
                score,
                null,
                null,
                false,
                false,
                false,
                false,
                false,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                false
        );
    }

    public EvidenceItem(String evidenceId,
                        String paperId,
                        String paperTitle,
                        String originalFilename,
                        Integer pageNumber,
                        Integer chunkId,
                        String sourceKind,
                        String sectionTitle,
                        String matchedText,
                        String bboxJson,
                        Double score,
                        String sourceType,
                        String evidenceAssetLevel,
                        Boolean pdfEvidenceAvailable,
                        Boolean structuredImport,
                        Boolean evalImport,
                        Boolean pageScreenshotAvailable,
                        Boolean figureScreenshotAvailable,
                        List<String> assetWarnings) {
        this(
                evidenceId,
                paperId,
                paperTitle,
                originalFilename,
                pageNumber,
                chunkId,
                sourceKind,
                sectionTitle,
                matchedText,
                bboxJson,
                score,
                sourceType,
                evidenceAssetLevel,
                pdfEvidenceAvailable,
                structuredImport,
                evalImport,
                pageScreenshotAvailable,
                figureScreenshotAvailable,
                assetWarnings,
                null,
                null,
                null,
                null,
                null,
                null,
                false
        );
    }
}
