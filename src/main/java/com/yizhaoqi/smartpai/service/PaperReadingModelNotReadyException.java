package com.yizhaoqi.smartpai.service;

public class PaperReadingModelNotReadyException extends RuntimeException {
    public PaperReadingModelNotReadyException(String message) {
        super(message);
    }
}
