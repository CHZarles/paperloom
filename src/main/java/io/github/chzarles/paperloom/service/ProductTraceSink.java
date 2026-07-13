package io.github.chzarles.paperloom.service;

public interface ProductTraceSink {

    void submit(ProductTracePayload payload);
}
