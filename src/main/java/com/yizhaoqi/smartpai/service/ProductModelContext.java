package com.yizhaoqi.smartpai.service;

public record ProductModelContext(
        int maxReActRounds,
        int maxCompletionTokens,
        int maxHistoryCharacters,
        int recentHistoryMessages
) {
    public static final int DEFAULT_MAX_REACT_ROUNDS = 6;

    public ProductModelContext {
        maxReActRounds = maxReActRounds <= 0 ? DEFAULT_MAX_REACT_ROUNDS : maxReActRounds;
        maxCompletionTokens = maxCompletionTokens <= 0 ? 1600 : maxCompletionTokens;
        maxHistoryCharacters = maxHistoryCharacters <= 0 ? 16000 : maxHistoryCharacters;
        recentHistoryMessages = recentHistoryMessages <= 0 ? 12 : recentHistoryMessages;
    }

    public ProductModelContext(int maxReActRounds, int maxCompletionTokens) {
        this(maxReActRounds, maxCompletionTokens, 16000, 12);
    }

    public static ProductModelContext defaults() {
        return new ProductModelContext(DEFAULT_MAX_REACT_ROUNDS, 1600, 16000, 12);
    }
}
