package com.yizhaoqi.smartpai.service;

import java.util.Locale;

public enum RetrievalBudgetProfile {
    INTERACTIVE,
    HIGH_RECALL,
    DEEP_AUDIT;

    public static RetrievalBudgetProfile fromToken(String token) {
        if (token == null || token.isBlank()) {
            return INTERACTIVE;
        }
        String normalized = token.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "high_recall", "highrecall", "k5" -> HIGH_RECALL;
            case "deep_audit", "deepaudit", "deep_recall", "deeprecall", "audit", "k7" -> DEEP_AUDIT;
            default -> INTERACTIVE;
        };
    }

    public RetrievalBudget qaBudget() {
        return switch (this) {
            case HIGH_RECALL -> RetrievalBudget.forQaHighRecallPageWindows();
            case DEEP_AUDIT -> RetrievalBudget.forQaDeepAuditPageWindows();
            case INTERACTIVE -> RetrievalBudget.forQa();
        };
    }
}
