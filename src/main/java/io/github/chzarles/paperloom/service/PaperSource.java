package io.github.chzarles.paperloom.service;

public record PaperSource(
        String paperId,
        String paperTitle,
        String originalFilename
) {
}
