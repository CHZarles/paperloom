package io.github.chzarles.paperloom.eval;

public record PaperPageChunk(
        String paperId,
        String paperTitle,
        String originalFilename,
        Integer pageNumber,
        Integer chunkId,
        String sectionTitle,
        String sourceKind,
        String tableId,
        String figureId,
        String text
) {
    public PaperPageChunk {
        paperId = paperId == null ? "" : paperId;
        paperTitle = paperTitle == null ? "" : paperTitle;
        originalFilename = originalFilename == null ? "" : originalFilename;
        sectionTitle = sectionTitle == null ? "" : sectionTitle;
        sourceKind = sourceKind == null ? "TEXT" : sourceKind;
        text = text == null ? "" : text;
    }
}
