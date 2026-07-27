package io.github.chzarles.paperloom.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

public abstract class UsageQuotaService {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final double ASCII_TOKEN_RATIO = 0.30d;
    private static final double CJK_TOKEN_RATIO = 0.95d;
    private static final double OTHER_TOKEN_RATIO = 0.55d;

    public abstract TokenReservation reserveLlmTokens(String userId, int estimatedPromptTokens, int maxCompletionTokens);

    public abstract TokenReservation reserveEmbeddingTokens(String userId, List<String> texts);

    public abstract void recordChatRequest(String userId);

    public abstract void settleReservation(TokenReservation reservation, int actualTokens);

    public void abortReservation(TokenReservation reservation) {
        // Balance mode does not mutate state during reserve, so there is nothing to roll back.
    }

    public UserUsageSnapshot getSnapshot(String userId) {
        Map<String, UserUsageSnapshot> snapshots = getSnapshots(List.of(userId));
        return snapshots.getOrDefault(userId, emptySnapshot());
    }

    public abstract Map<String, UserUsageSnapshot> getSnapshots(List<String> userIds);

    public abstract List<DailyUsageAggregate> getDailyAggregates(List<String> userIds, int days);

    public int estimateChatTokens(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (Map<String, String> message : messages) {
            total += 8;
            total += estimateTextTokens(message.get("role"));
            total += estimateTextTokens(message.get("content"));
        }
        return total;
    }

    public int estimateEmbeddingTokens(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (String text : texts) {
            total += estimateTextTokens(text) + 4;
        }
        return (int) Math.ceil(total * 1.15d);
    }

    public int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        int ascii = 0;
        int cjk = 0;
        int other = 0;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }

            Character.UnicodeScript script = Character.UnicodeScript.of(current);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk++;
            } else if (current <= 0x7F) {
                ascii++;
            } else {
                other++;
            }
        }

        double estimated = ascii * ASCII_TOKEN_RATIO + cjk * CJK_TOKEN_RATIO + other * OTHER_TOKEN_RATIO + 12;
        return Math.max(1, (int) Math.ceil(estimated));
    }

    protected String currentDay() {
        return ZonedDateTime.now(ZoneId.systemDefault()).format(DAY_FORMATTER);
    }

    private UserUsageSnapshot emptySnapshot() {
        return new UserUsageSnapshot(currentDay(),
                0,
                new QuotaView(false, 0, 0, 0, 0),
                new QuotaView(false, 0, 0, 0, 0));
    }

    public record TokenReservation(
            String scope,
            String userId,
            String quotaKey,
            String metricKey,
            long reservedTokens,
            long limit,
            long expiresInSeconds,
            boolean noop,
            boolean retainHistory
    ) {
        public static TokenReservation noop(String scope, String userId) {
            return new TokenReservation(scope, userId, "", "", 0, 0, 0, true, false);
        }
    }

    public record QuotaView(
            boolean enabled,
            long usedTokens,
            long limitTokens,
            long remainingTokens,
            long requestCount
    ) {
    }

    public record UserUsageSnapshot(
            String day,
            long chatRequestCount,
            QuotaView llm,
            QuotaView embedding
    ) {
    }

    public record DailyUsageAggregate(
            String day,
            long chatRequestCount,
            long llmUsedTokens,
            long llmRequestCount,
            long embeddingUsedTokens,
            long embeddingRequestCount
    ) {
    }
}
