package com.yizhaoqi.smartpai.service;

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
        Double score
) {
}
