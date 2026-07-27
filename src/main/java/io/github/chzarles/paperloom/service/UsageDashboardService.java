package io.github.chzarles.paperloom.service;

import java.util.List;

public abstract class UsageDashboardService {

    public abstract UsageOverview buildOverview(int days);

    public record UsageOverview(
            int days,
            DailyUsagePoint today,
            List<DailyUsagePoint> trends,
            List<UsageRankingItem> llmRankings,
            List<UsageRankingItem> embeddingRankings,
            List<UsageAlert> alerts
    ) {
    }

    public record DailyUsagePoint(
            String day,
            long chatRequestCount,
            long llmUsedTokens,
            long llmRequestCount,
            long embeddingUsedTokens,
            long embeddingRequestCount
    ) {
    }

    public record UsageRankingItem(
            String userId,
            String username,
            String scope,
            long usedTokens,
            long limitTokens,
            long remainingTokens,
            long requestCount
    ) {
    }

    public record UsageAlert(
            String level,
            String userId,
            String username,
            String scope,
            long usedTokens,
            long limitTokens,
            long remainingTokens,
            long requestCount,
            double usageRatio,
            String message
    ) {
    }
}
