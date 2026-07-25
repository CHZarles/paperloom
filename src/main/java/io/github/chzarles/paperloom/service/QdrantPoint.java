package io.github.chzarles.paperloom.service;

import java.util.Map;

public record QdrantPoint(
        String id,
        QdrantSparseVector lexicalVector,
        float[] denseVector,
        Map<String, Object> payload
) {
    public QdrantPoint {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        denseVector = denseVector == null ? null : denseVector.clone();
    }

    public QdrantPoint(String id, QdrantSparseVector lexicalVector, Map<String, Object> payload) {
        this(id, lexicalVector, null, payload);
    }

    public boolean hasDenseVector() {
        return denseVector != null;
    }
}
