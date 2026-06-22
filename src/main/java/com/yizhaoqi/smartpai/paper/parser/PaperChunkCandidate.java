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
        String rawProvenanceJson,
        String sourceKind,
        String tableId,
        String figureId,
        String formulaId,
        String evidenceRole
) {
    public PaperChunkCandidate(Integer chunkId,
                               String text,
                               Integer pageNumber,
                               String anchorText,
                               String sectionTitle,
                               Integer sectionLevel,
                               String elementType,
                               String bboxJson,
                               String parserName,
                               String parserVersion,
                               String rawProvenanceJson) {
        this(chunkId, text, pageNumber, anchorText, sectionTitle, sectionLevel, elementType, bboxJson,
                parserName, parserVersion, rawProvenanceJson, "TEXT", null, null, null, "NORMAL_TEXT");
    }

    public PaperChunkCandidate(Integer chunkId,
                               String text,
                               Integer pageNumber,
                               String anchorText,
                               String sectionTitle,
                               Integer sectionLevel,
                               String elementType,
                               String bboxJson,
                               String parserName,
                               String parserVersion,
                               String rawProvenanceJson,
                               String sourceKind,
                               String tableId) {
        this(chunkId, text, pageNumber, anchorText, sectionTitle, sectionLevel, elementType, bboxJson,
                parserName, parserVersion, rawProvenanceJson, sourceKind, tableId, null, null, "NORMAL_TEXT");
    }
}
