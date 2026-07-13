package io.github.chzarles.paperloom.paper.parser;

public record ParsedPaperArtifactPayload(
        String artifactType,
        String filename,
        String contentType,
        byte[] bytes
) {
}
