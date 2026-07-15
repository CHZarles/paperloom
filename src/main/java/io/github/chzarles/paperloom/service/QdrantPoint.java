package io.github.chzarles.paperloom.service;

import java.util.Map;

public record QdrantPoint(
        String id,
        float[] denseVector,
        QdrantSparseVector sparseVector,
        Map<String, Object> payload
) {
    public QdrantPoint {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
