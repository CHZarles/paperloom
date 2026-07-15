package io.github.chzarles.paperloom.service;

import java.util.List;

public record QdrantSparseVector(List<Integer> indices, List<Float> values) {
    public QdrantSparseVector {
        indices = indices == null ? List.of() : List.copyOf(indices);
        values = values == null ? List.of() : List.copyOf(values);
        if (indices.size() != values.size()) {
            throw new IllegalArgumentException("Sparse vector indices and values must have the same size");
        }
    }
}
