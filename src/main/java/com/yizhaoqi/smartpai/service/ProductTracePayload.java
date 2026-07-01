package com.yizhaoqi.smartpai.service;

import java.util.Map;

public record ProductTracePayload(
        String conversationId,
        String generationId,
        String artifactType,
        String filename,
        Map<String, Object> traceJson
) {
}
