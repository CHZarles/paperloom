package io.github.chzarles.paperloom.service;

import java.time.Duration;

public record RetrievalBudget(
        Duration latencyBudget,
        int contextTokenBudget,
        double minScore,
        double scorePlateauDelta,
        int pageBatchSize,
        int pageWindowTopK,
        int pageWindowRadius,
        String pageWindowPlanner
) {
    private static final int INTERACTIVE_PAGE_WINDOW_TOP_K = 3;
    private static final int HIGH_RECALL_PAGE_WINDOW_TOP_K = 5;
    private static final int DEEP_AUDIT_PAGE_WINDOW_TOP_K = 7;
    private static final int DEFAULT_PAGE_WINDOW_RADIUS = 1;
    private static final String DEFAULT_PAGE_WINDOW_PLANNER = "scientific-qa-diverse-windows";

    public RetrievalBudget {
        latencyBudget = latencyBudget == null ? Duration.ofSeconds(5) : latencyBudget;
        contextTokenBudget = Math.max(512, contextTokenBudget);
        minScore = Math.max(0.0d, minScore);
        scorePlateauDelta = Math.max(0.0d, scorePlateauDelta);
        pageBatchSize = Math.max(1, pageBatchSize);
        pageWindowTopK = Math.max(1, pageWindowTopK);
        pageWindowRadius = Math.max(0, pageWindowRadius);
        pageWindowPlanner = pageWindowPlanner == null || pageWindowPlanner.isBlank()
                ? DEFAULT_PAGE_WINDOW_PLANNER
                : pageWindowPlanner;
    }

    public RetrievalBudget(
            Duration latencyBudget,
            int contextTokenBudget,
            double minScore,
            double scorePlateauDelta,
            int pageBatchSize
    ) {
        this(
                latencyBudget,
                contextTokenBudget,
                minScore,
                scorePlateauDelta,
                pageBatchSize,
                INTERACTIVE_PAGE_WINDOW_TOP_K,
                DEFAULT_PAGE_WINDOW_RADIUS,
                DEFAULT_PAGE_WINDOW_PLANNER
        );
    }

    public static RetrievalBudget forQa() {
        return new RetrievalBudget(
                Duration.ofSeconds(6),
                9_000,
                0.3d,
                0.03d,
                24,
                INTERACTIVE_PAGE_WINDOW_TOP_K,
                DEFAULT_PAGE_WINDOW_RADIUS,
                DEFAULT_PAGE_WINDOW_PLANNER
        );
    }

    public static RetrievalBudget forQaHighRecallPageWindows() {
        return new RetrievalBudget(
                Duration.ofSeconds(6),
                12_000,
                0.3d,
                0.03d,
                24,
                HIGH_RECALL_PAGE_WINDOW_TOP_K,
                DEFAULT_PAGE_WINDOW_RADIUS,
                DEFAULT_PAGE_WINDOW_PLANNER
        );
    }

    public static RetrievalBudget forQaDeepAuditPageWindows() {
        return new RetrievalBudget(
                Duration.ofSeconds(8),
                16_000,
                0.3d,
                0.03d,
                24,
                DEEP_AUDIT_PAGE_WINDOW_TOP_K,
                DEFAULT_PAGE_WINDOW_RADIUS,
                DEFAULT_PAGE_WINDOW_PLANNER
        );
    }

    public static RetrievalBudget forLibrarySearch() {
        return new RetrievalBudget(Duration.ofSeconds(6), 12_000, 0.3d, 0.03d, 32);
    }

    public static RetrievalBudget forPageBatch(int pageBatchSize) {
        return new RetrievalBudget(Duration.ofSeconds(6), 9_000, 0.3d, 0.03d, pageBatchSize);
    }
}
