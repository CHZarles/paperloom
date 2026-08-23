package io.github.chzarles.paperloom.service;

public final class PaperReadingException extends RuntimeException {

    private final String code;

    public PaperReadingException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String safeMessage() {
        return getMessage();
    }
}
