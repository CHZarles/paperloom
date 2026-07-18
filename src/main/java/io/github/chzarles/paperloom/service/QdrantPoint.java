package io.github.chzarles.paperloom.service;

import java.util.Map;

public record QdrantPoint(
        String id,
        QdrantSparseVector lexicalVector,
        Map<String, Object> payload
) {
    public QdrantPoint {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
