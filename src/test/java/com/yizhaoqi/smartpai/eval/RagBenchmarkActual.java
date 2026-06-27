package com.yizhaoqi.smartpai.eval;

import com.yizhaoqi.smartpai.service.ChatHandler;

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
