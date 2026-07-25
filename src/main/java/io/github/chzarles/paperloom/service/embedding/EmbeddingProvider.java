package io.github.chzarles.paperloom.service.embedding;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Embedding model abstraction. Implementations encode a batch of texts into
 * dense float vectors of fixed {@link #dimension()}.
 */
public interface EmbeddingProvider {

    Mono<List<float[]>> embed(List<String> texts);

    String modelName();

    int dimension();
}
