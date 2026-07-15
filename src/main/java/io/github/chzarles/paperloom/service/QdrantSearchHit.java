package io.github.chzarles.paperloom.service;

import java.util.Map;

public record QdrantSearchHit(String id, double score, Map<String, Object> payload) {
    public QdrantSearchHit {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
