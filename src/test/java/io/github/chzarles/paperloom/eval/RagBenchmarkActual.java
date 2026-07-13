package io.github.chzarles.paperloom.eval;

import io.github.chzarles.paperloom.service.ChatHandler;

import java.util.Map;

public record RagBenchmarkActual(
        String route,
        String markdown,
        Map<Integer, ChatHandler.ReferenceInfo> referenceMappings,
        Map<String, Object> diagnostics
) {
    public RagBenchmarkActual {
        markdown = markdown == null ? "" : markdown;
        referenceMappings = referenceMappings == null ? Map.of() : referenceMappings;
        diagnostics = diagnostics == null ? Map.of() : diagnostics;
    }
}
