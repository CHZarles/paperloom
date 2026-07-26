package io.github.chzarles.paperloom.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ResearchHarnessTransport {

    ProductTurnResult run(ProductTurnRequest request);

    CompletableFuture<ProductTurnResult> submit(
            ProductTurnRequest request,
            Consumer<Map<String, Object>> progressListener
    );

    void cancel(String generationId);
}
