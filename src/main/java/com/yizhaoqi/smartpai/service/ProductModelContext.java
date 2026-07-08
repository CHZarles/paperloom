package com.yizhaoqi.smartpai.service;

public record ProductModelContext(
        int maxReActRounds,
        int maxCompletionTokens,
        int maxHistoryCharacters,
        int recentHistoryMessages
) {
    public static final int DEFAULT_MAX_REACT_ROUNDS = 100;
    public static final int UNLIMITED_MAX_COMPLETION_TOKENS = 0;

    public ProductModelContext {
        maxReActRounds = maxReActRounds <= 0 ? DEFAULT_MAX_REACT_ROUNDS : maxReActRounds;
        maxCompletionTokens = maxCompletionTokens < 0 ? UNLIMITED_MAX_COMPLETION_TOKENS : maxCompletionTokens;
        maxHistoryCharacters = maxHistoryCharacters <= 0 ? 16000 : maxHistoryCharacters;
        recentHistoryMessages = recentHistoryMessages <= 0 ? 12 : recentHistoryMessages;
    }

    public ProductModelContext(int maxReActRounds, int maxCompletionTokens) {
        this(maxReActRounds, maxCompletionTokens, 16000, 12);
    }

    public static ProductModelContext defaults() {
        return new ProductModelContext(DEFAULT_MAX_REACT_ROUNDS, UNLIMITED_MAX_COMPLETION_TOKENS, 16000, 12);
    }
}
