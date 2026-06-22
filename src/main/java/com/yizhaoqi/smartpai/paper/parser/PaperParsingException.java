package com.yizhaoqi.smartpai.paper.parser;

public class PaperParsingException extends RuntimeException {

    public PaperParsingException(String message) {
        super(message);
    }

    public PaperParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
