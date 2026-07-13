package io.github.chzarles.paperloom.paper.parser;

public class MinerUUnavailableException extends PaperParsingException {

    public MinerUUnavailableException(String message) {
        super(message);
    }

    public MinerUUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
