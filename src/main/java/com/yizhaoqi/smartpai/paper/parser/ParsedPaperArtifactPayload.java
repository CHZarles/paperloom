package com.yizhaoqi.smartpai.paper.parser;

public record ParsedPaperArtifactPayload(
        String artifactType,
        String filename,
        String contentType,
        byte[] bytes
) {
}
