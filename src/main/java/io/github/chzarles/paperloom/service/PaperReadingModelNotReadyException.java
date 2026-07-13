package io.github.chzarles.paperloom.service;

public class PaperReadingModelNotReadyException extends RuntimeException {
    public PaperReadingModelNotReadyException(String message) {
        super(message);
    }
}
