package io.github.chzarles.paperloom.service;

public class PaperReadingModelValidationException extends RuntimeException {
    private final String failureReason;
    private final String diagnosticsJson;

    public PaperReadingModelValidationException(String failureReason, String diagnosticsJson) {
        super(failureReason);
        this.failureReason = failureReason;
        this.diagnosticsJson = diagnosticsJson;
    }

    public String failureReason() {
        return failureReason;
    }

    public String diagnosticsJson() {
        return diagnosticsJson;
    }
}
