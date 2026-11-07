package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.UsageQuotaProperties;
import io.github.chzarles.paperloom.exception.QuotaExceededException;
import io.github.chzarles.paperloom.model.DailyReqCountStat;
import io.github.chzarles.paperloom.model.DailyUsageStat;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.model.UserTokenRecord;
import io.github.chzarles.paperloom.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
/**
 * Bounded token-quota accounting for chat, LLM, and embedding usage.
 */@Service
public class UsageQuotaService {

    private static final Logger logger = LoggerFactory.getLogger(UsageQuotaService.class);
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final double ASCII_TOKEN_RATIO = 0.30d;
    private static final double CJK_TOKEN_RATIO = 0.95d;
    private static final double OTHER_TOKEN_RATIO = 0.55d;

    private static final String GUEST_QUOTA_SUBJECT_ID = "guest-pool";

    private final UserTokenService userTokenService;
    private final UserRepository userRepository;
    private final UsageQuotaProperties properties;

    public UsageQuotaService(UsageQuotaProperties properties,
                                    UserTokenService userTokenService,
                                    UserRepository userRepository
    ) {
        this.properties = properties;
        this.userTokenService = userTokenService;
        this.userRepository = userRepository;
    }

    public TokenReservation reserveLlmTokens(String userId, int estimatedPromptTokens, int maxCompletionTokens) {
        if (!isQuotaManaged(userId) || !properties.getLlm().isEnabled()) {
            return TokenReservation.noop("llm", userId);
        }

        int reserveTokens = Math.max(estimatedPromptTokens, 0) + Math.max(maxCompletionTokens, 0);
        reserveTokens = Math.max(reserveTokens, 1);
        String quotaSubjectId = resolveQuotaSubjectId(userId);

        if (!userTokenService.reserveLlmTokens(quotaSubjectId, reserveTokens)) {
            Long balance = userTokenService.getLlmTokenBalance(quotaSubjectId);
            throw new QuotaExceededException(
                    "LLM Token 余额不足，预估需要：" + reserveTokens + ", 当前余额：" + balance, 0);
        }

        return new TokenReservation("llm", quotaSubjectId, reserveTokens, false, "");
    }

    public TokenReservation reserveEmbeddingTokens(String userId, List<String> texts) {
        if (!isQuotaManaged(userId) || !properties.getEmbedding().isEnabled()) {
            return TokenReservation.noop("embedding", userId);
        }

        int estimatedTokens = Math.max(estimateEmbeddingTokens(texts), 1);
        String quotaSubjectId = resolveQuotaSubjectId(userId);

        if (!userTokenService.reserveEmbeddingTokens(quotaSubjectId, estimatedTokens)) {
            Long balance = userTokenService.getEmbeddingTokenBalance(quotaSubjectId);
            throw new QuotaExceededException(
                    "Embedding Token 余额不足，预估需要：" + estimatedTokens + ", 当前余额：" + balance, 0);
        }

        return new TokenReservation("embedding", quotaSubjectId, estimatedTokens, false, "");
    }

    /**
     * 记录用户聊天请求次数
     * @param userId
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordChatRequest(String userId) {
        if (!isQuotaManaged(userId)) {
            return;
        }

        userTokenService.updateUserDailyChatCount(userId, LocalDate.now());
    }

    /**
     * 结算用户的token消耗
     *
     * @param reservation
     * @param actualTokens
     */
    @Transactional(rollbackFor = Exception.class)
    public void settleReservation(TokenReservation reservation, int actualTokens) {
        if (reservation == null || reservation.noop()) {
            return;
        }

        try {
            boolean applied = "llm".equals(reservation.scope())
                    ? userTokenService.settleLlmTokenReservation(reservation.userId(),
                            reservation.reservationId(), reservation.reservedTokens(), actualTokens)
                    : userTokenService.settleEmbeddingTokenReservation(reservation.userId(),
                            reservation.reservationId(), reservation.reservedTokens(), actualTokens);
            if (applied && actualTokens > 0) {
                userTokenService.incrementUserTotalRequestCount(reservation.scope(), reservation.userId());
            }
            logger.info("用户 {} 结算 {} Token: {}, 生效={}",
                    reservation.userId(), reservation.scope(), actualTokens, applied);
        } catch (Exception e) {
            logger.error("结算用户 Token 失败：userId={}, scope={}, tokens={}",
                    reservation.userId(), reservation.scope(), actualTokens, e);
            throw e;
        }
    }

    public void abortReservation(TokenReservation reservation) {
        if (reservation == null || reservation.noop()) {
            return;
        }
        if ("llm".equals(reservation.scope())) {
            userTokenService.releaseLlmTokenReservation(
                    reservation.userId(), reservation.reservationId(), reservation.reservedTokens());
        } else if ("embedding".equals(reservation.scope())) {
            userTokenService.releaseEmbeddingTokenReservation(
                    reservation.userId(), reservation.reservationId(), reservation.reservedTokens());
        }
    }


    /**
     * 获取用户使用情况快照
     * @param userIds
     * @return
     */
    public Map<String, UserUsageSnapshot> getSnapshots(List<String> userIds) {
        Map<String, UserUsageSnapshot> result = new LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) {
            return result;
        }

        Set<String> guestUserIds = findGuestUserIds(userIds);
        for (String userId : userIds) {
            String quotaSubjectId = guestUserIds.contains(userId) ? GUEST_QUOTA_SUBJECT_ID : userId;
            result.put(userId, new UserUsageSnapshot(
                    currentDay(),
                    userTokenService.getUserDailyChatCount(userId, LocalDate.now()),
                    buildQuotaView("llm", quotaSubjectId, properties.getLlm()),
                    buildQuotaView("embedding", quotaSubjectId, properties.getEmbedding())
            ));
        }
        return result;
    }

    private Set<String> findGuestUserIds(List<String> userIds) {
        List<Long> numericUserIds = new ArrayList<>();
        for (String userId : userIds) {
            try {
                numericUserIds.add(Long.parseLong(userId));
            } catch (NumberFormatException ignored) {
                // Non-user subjects such as system jobs keep their original quota identity.
            }
        }

        Set<String> guestUserIds = new HashSet<>();
        for (User user : userRepository.findAllById(numericUserIds)) {
            if (user.getRole() == User.Role.GUEST) {
                guestUserIds.add(String.valueOf(user.getId()));
            }
        }
        return guestUserIds;
    }

    /**
     * 获取用户额度视图
     * @param scope
     * @param userId
     * @param quota
     * @return
     */
    private QuotaView buildQuotaView(String scope, String userId, UsageQuotaProperties.TokenQuota quota) {
        if (!isQuotaManaged(userId) || !quota.isEnabled()) {
            return new QuotaView(false, 0, 0, 0, 0);
        }

        // 用户的总token
        long limit, balance;
        if ("llm".equals(scope)) {
            balance = userTokenService.getLlmTokenBalance(userId);
            limit = userTokenService.getUserLlmTotalIncreaseTokens(userId);
        } else {
            balance = userTokenService.getEmbeddingTokenBalance(userId);
            limit = userTokenService.getUserEmbeddingTotalIncreaseTokens(userId);
        }
        long usedTokens = limit - balance;
        // 请求次数
        long requestCount = userTokenService.getUserTotalRequestCount(scope, userId);
        return new QuotaView(true, usedTokens, limit, balance, requestCount);
    }

    private boolean isQuotaManaged(String userId) {
        return userId != null && !userId.isBlank() && !userId.startsWith("system");
    }

    private String resolveQuotaSubjectId(String userId) {
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .filter(user -> user.getRole() == User.Role.GUEST)
                    .map(user -> GUEST_QUOTA_SUBJECT_ID)
                    .orElse(userId);
        } catch (NumberFormatException ignored) {
            return userId;
        }
    }


    /**
     * 用于后台统计每天的使用量
     *
     * @param userIds
     * @param days
     * @return
     */
    public List<DailyUsageAggregate> getDailyAggregates(List<String> userIds, int days) {
        int normalizedDays = Math.max(1, Math.min(days, properties.getRetentionDays()));
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        LocalDate startDay = today.minusDays(normalizedDays - 1);

        // 查询所有用户，在这个时间段内的数据统计
        List<DailyUsageStat> llmStat = userTokenService.getDailyStatsByType(startDay, today, UserTokenRecord.TokenType.LLM);
        List<DailyUsageStat> embeddingStat = userTokenService.getDailyStatsByType(startDay, today, UserTokenRecord.TokenType.EMBEDDING);

        List<DailyReqCountStat> reqCountStat = userTokenService.getDailyReqCountStats(startDay, today);

        // 将上面的数据，聚合成结果返回，需要开始时间到结束时间，如果中间缺了日期，则需要自动补全
        List<DailyUsageAggregate> result = new ArrayList<>();

        // 构建日期到统计数据的映射
        java.util.Map<LocalDate, DailyUsageStat> llmStatMap = llmStat.stream()
                .collect(java.util.stream.Collectors.toMap(
                        DailyUsageStat::recordDate,
                        stat -> stat,
                        (existing, replacement) -> existing
                ));

        java.util.Map<LocalDate, DailyUsageStat> embeddingStatMap = embeddingStat.stream()
                .collect(java.util.stream.Collectors.toMap(
                        DailyUsageStat::recordDate,
                        stat -> stat,
                        (existing, replacement) -> existing
                ));

        java.util.Map<LocalDate, DailyReqCountStat> reqCountStatMap = reqCountStat.stream()
                .collect(java.util.stream.Collectors.toMap(
                        DailyReqCountStat::recordDate,
                        stat -> stat,
                        (existing, replacement) -> existing
                ));

        // 遍历所有日期，生成聚合结果
        for (LocalDate day = startDay; !day.isAfter(today); day = day.plusDays(1)) {
            String dayString = day.toString();

            DailyUsageStat llmStatForDay = llmStatMap.get(day);
            DailyUsageStat embeddingStatForDay = embeddingStatMap.get(day);
            DailyReqCountStat reqCountStatForDay = reqCountStatMap.get(day);

            result.add(new DailyUsageAggregate(
                    dayString,
                    reqCountStatForDay != null ? reqCountStatForDay.totalRequestCount() : 0L,
                    llmStatForDay != null ? llmStatForDay.totalAmount() : 0L,
                    llmStatForDay != null ? llmStatForDay.totalRequestCount() : 0L,
                    embeddingStatForDay != null ? embeddingStatForDay.totalAmount() : 0L,
                    embeddingStatForDay != null ? embeddingStatForDay.totalRequestCount() : 0L
            ));
        }

        return result;
    }
    public UserUsageSnapshot getSnapshot(String userId) {
        Map<String, UserUsageSnapshot> snapshots = getSnapshots(List.of(userId));
        return snapshots.getOrDefault(userId, emptySnapshot());
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

    private String currentDay() {
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
            long reservedTokens,
            boolean noop,
            String reservationId
    ) {
        public TokenReservation {
            if (reservationId == null || reservationId.isBlank()) {
                reservationId = UUID.randomUUID().toString();
            }
        }

        public static TokenReservation noop(String scope, String userId) {
            return new TokenReservation(scope, userId, 0, true, "");
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
