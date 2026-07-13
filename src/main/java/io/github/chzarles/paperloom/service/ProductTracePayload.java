package io.github.chzarles.paperloom.service;

import java.util.Map;

public record ProductTracePayload(
        String conversationId,
        String generationId,
        String artifactType,
        String filename,
        Map<String, Object> traceJson
) {
}
