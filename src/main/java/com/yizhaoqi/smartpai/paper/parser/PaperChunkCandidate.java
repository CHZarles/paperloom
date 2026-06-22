package com.yizhaoqi.smartpai.paper.parser;

public record PaperChunkCandidate(
        Integer chunkId,
        String text,
        Integer pageNumber,
        String anchorText,
        String sectionTitle,
        Integer sectionLevel,
        String elementType,
        String bboxJson,
        String parserName,
        String parserVersion,
        String rawProvenanceJson
) {
}
