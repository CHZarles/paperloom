package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.UsageQuotaProperties;
import io.github.chzarles.paperloom.exception.CustomException;
import io.github.chzarles.paperloom.model.DailyReqCountStat;
import io.github.chzarles.paperloom.model.DailyUsageStat;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.model.UserDailyChatCount;
import io.github.chzarles.paperloom.model.UserTokenRecord;
import io.github.chzarles.paperloom.repository.UserDailyChatCountRepository;
import io.github.chzarles.paperloom.repository.UserTokenRecordRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 用户 Token 管理服务
 * 负责管理用户的全局可用 Token（LLM Token 和 Embedding Token）
 *
 * @author YiHui
 * @date 2026/3/18
 */
@Slf4j
@Service
public class UserTokenService {

    private static final Logger logger = LoggerFactory.getLogger(UserTokenService.class);

    /**
     * Redis Key 前缀：user:token:llm:{userId}
     */
    private static final String LLM_TOKEN_KEY_PREFIX = "user:token:llm:";

    /**
     * Redis Key 前缀：user:token:embedding:{userId}
     */
    private static final String EMBEDDING_TOKEN_KEY_PREFIX = "user:token:embedding:";

    private static final DefaultRedisScript<Long> TOKEN_RESERVATION_SCRIPT = new DefaultRedisScript<>(
            "local balance=tonumber(redis.call('GET', KEYS[1]) or '0'); "
                    + "local amount=tonumber(ARGV[1]); "
                    + "if balance < amount then return -1 end; "
                    + "return redis.call('DECRBY', KEYS[1], amount)",
            Long.class
    );

    /**
     * 结束一次预留。写状态标记和调整余额在同一个脚本里完成，重复调用只生效一次。
     *
     * <p>KEYS[1] 余额键，KEYS[2] 状态标记键。
     * <p>ARGV[1] 状态值，ARGV[2] 标记过期秒数，ARGV[3] 余额调整量。
     * <p>返回 0 表示这次预留已经结束过。
     */
    private static final DefaultRedisScript<Long> RESERVATION_FINISH_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[2]) == 1 then return 0 end; "
                    + "local adjustment=tonumber(ARGV[3]); "
                    + "if adjustment ~= 0 then redis.call('INCRBY', KEYS[1], adjustment) end; "
                    + "redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[2]); "
                    + "return 1",
            Long.class
    );

    /**
     * Redis Key 前缀：quota:reservation:{reservationId}
     */
    private static final String RESERVATION_STATE_KEY_PREFIX = "quota:reservation:";

    private static final String RESERVATION_SETTLED = "SETTLED";

    private static final String RESERVATION_RELEASED = "RELEASED";

    /**
     * 状态标记的存活时间。必须大于最长重试窗口，否则过期后重复结算会再次生效。
     */
    private static final Duration RESERVATION_STATE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;

    private final UsageQuotaProperties usageQuotaProperties;

    private final UserTokenRecordRepository userTokenRecordRepository;

    private final UserDailyChatCountRepository userDailyChatCountRepository;

    private final UserRepository userRepository;

    private final UserTokenLedger userTokenLedger;

    public UserTokenService(StringRedisTemplate stringRedisTemplate,
                            UsageQuotaProperties usageQuotaProperties,
                            UserTokenRecordRepository userTokenRecordRepository,
                            UserDailyChatCountRepository userDailyChatCountRepository,
                            UserRepository userRepository,
                            UserTokenLedger userTokenLedger) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.usageQuotaProperties = usageQuotaProperties;
        this.userTokenRecordRepository = userTokenRecordRepository;
        this.userDailyChatCountRepository = userDailyChatCountRepository;
        this.userRepository = userRepository;
        this.userTokenLedger = userTokenLedger;
    }

    /**
     * 获取用户的 LLM Token 余额
     *
     * @param userId 用户 ID
     * @return LLM Token 余额
     */
    public Long getLlmTokenBalance(String userId) {
        String key = buildLlmTokenKey(userId);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(value)) {
            value = initializeBalance(key, userId, usageQuotaProperties.getLlm(), UserTokenRecord.TokenType.LLM);
        }
        return parseBalance(userId, value, "LLM");
    }

    /**
     * 获取用户的 Embedding Token 余额
     *
     * @param userId 用户 ID
     * @return Embedding Token 余额
     */
    public Long getEmbeddingTokenBalance(String userId) {
        String key = buildEmbeddingTokenKey(userId);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(value)) {
            value = initializeBalance(key, userId, usageQuotaProperties.getEmbedding(), UserTokenRecord.TokenType.EMBEDDING);
        }
        return parseBalance(userId, value, "Embedding");
    }

    /**
     * 余额键不存在时初始化。
     *
     * <p>先按记账表重建。记账表没有任何增加记录时，才按新用户发放初始额度。
     * 这样「新用户」和「Redis 数据丢失」不会被当成同一种情况。
     */
    private String initializeBalance(String key,
                                     String userId,
                                     UsageQuotaProperties.TokenQuota quota,
                                     UserTokenRecord.TokenType tokenType) {
        Long rebuilt = rebuildBalanceFromLedger(userId, tokenType);
        if (rebuilt != null) {
            stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(rebuilt));
            logger.warn("{} Token 余额键缺失，已按记账表重建：userId={}, balance={}", tokenType, userId, rebuilt);
            return stringRedisTemplate.opsForValue().get(key);
        }

        long initToken = resolveInitToken(userId, quota);
        if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, String.valueOf(initToken)))) {
            recordTokenIncrease(userId, tokenType, initToken, 0L, initToken, "注册赠送", null);
            return String.valueOf(initToken);
        }
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 用记账表算余额：增加总量减消耗总量。
     *
     * @return 余额；记账表没有增加记录时返回 {@code null}，表示这是新用户。
     */
    private Long rebuildBalanceFromLedger(String userId, UserTokenRecord.TokenType tokenType) {
        long increased = userTokenRecordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                userId, tokenType, UserTokenRecord.ChangeType.INCREASE);
        if (increased <= 0) {
            return null;
        }
        long consumed = userTokenRecordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                userId, tokenType, UserTokenRecord.ChangeType.CONSUME);
        long balance = increased - consumed;
        if (balance < 0) {
            logger.warn("{} Token 记账表算出的余额为负，按 0 处理：userId={}, increased={}, consumed={}",
                    tokenType, userId, increased, consumed);
            return 0L;
        }
        return balance;
    }

    private Long parseBalance(String userId, String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            logger.warn("无法解析 {} Token 余额：userId={}, value={}", label, userId, value);
            return 0L;
        }
    }

    /**
     * 消耗用户的 LLM Token
     *
     * @param userId 用户 ID
     * @param tokens 消耗的 token 数量
     * @throws CustomException 如果余额不足或用户不存在
     */
    @Transactional(rollbackFor = Exception.class)
    public void consumeLlmTokens(String userId, int tokens) {
        if (tokens <= 0) {
            throw new CustomException("消耗的 Token 数量必须大于 0", HttpStatus.BAD_REQUEST);
        }

        String key = buildLlmTokenKey(userId);
        Long currentBalance = getLlmTokenBalance(userId);

        if (currentBalance < tokens) {
            logger.warn("用户 {} 的 LLM token 实际用量超过剩余额度：remain={}, consumer={}", userId, currentBalance, tokens);
        }

        stringRedisTemplate.opsForValue().increment(key, -tokens);
        logger.info("用户 {} 消耗 LLM Token: {}, 剩余：{}", userId, tokens, currentBalance - tokens);

        // 记录 Token 消耗（按天聚合）
        recordTokenConsume(userId, UserTokenRecord.TokenType.LLM, tokens, currentBalance, currentBalance - tokens);
    }

    public boolean reserveLlmTokens(String userId, long tokens) {
        if (tokens <= 0) {
            return true;
        }
        String key = buildLlmTokenKey(userId);
        getLlmTokenBalance(userId);
        Long remaining = stringRedisTemplate.execute(TOKEN_RESERVATION_SCRIPT,
                List.of(key), String.valueOf(tokens));
        return remaining != null && remaining >= 0;
    }

    /**
     * 结算 LLM 预留。同一次预留重复结算时只有第一次生效。
     *
     * @return {@code true} 表示本次结算生效；{@code false} 表示这次预留已经结束过。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean settleLlmTokenReservation(String userId,
                                             String reservationId,
                                             long reservedTokens,
                                             int actualTokens) {
        long reserved = Math.max(0, reservedTokens);
        int actual = Math.max(0, actualTokens);
        boolean applied = finishReservation(buildLlmTokenKey(userId), reservationId,
                reserved - actual, RESERVATION_SETTLED);
        if (!applied) {
            logger.warn("重复结算已忽略：userId={}, reservationId={}", userId, reservationId);
            return false;
        }
        if (actual > 0) {
            Long balanceAfter = getLlmTokenBalance(userId);
            recordTokenConsume(userId, UserTokenRecord.TokenType.LLM, actual,
                    balanceAfter + actual, balanceAfter);
        }
        return true;
    }

    /**
     * 退还 LLM 预留。同一次预留重复退还时只有第一次生效。
     *
     * @return {@code true} 表示本次退还有效；{@code false} 表示这次预留已经结束过。
     */
    public boolean releaseLlmTokenReservation(String userId, String reservationId, long tokens) {
        if (tokens <= 0) {
            return true;
        }
        boolean applied = finishReservation(buildLlmTokenKey(userId), reservationId,
                tokens, RESERVATION_RELEASED);
        if (!applied) {
            logger.warn("重复退还已忽略：userId={}, reservationId={}", userId, reservationId);
        }
        return applied;
    }

    public boolean reserveEmbeddingTokens(String userId, long tokens) {
        if (tokens <= 0) {
            return true;
        }
        String key = buildEmbeddingTokenKey(userId);
        getEmbeddingTokenBalance(userId);
        Long remaining = stringRedisTemplate.execute(TOKEN_RESERVATION_SCRIPT,
                List.of(key), String.valueOf(tokens));
        return remaining != null && remaining >= 0;
    }

    /**
     * 结算 Embedding 预留。同一次预留重复结算时只有第一次生效。
     *
     * @return {@code true} 表示本次结算生效；{@code false} 表示这次预留已经结束过。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean settleEmbeddingTokenReservation(String userId,
                                                   String reservationId,
                                                   long reservedTokens,
                                                   int actualTokens) {
        long reserved = Math.max(0, reservedTokens);
        int actual = Math.max(0, actualTokens);
        boolean applied = finishReservation(buildEmbeddingTokenKey(userId), reservationId,
                reserved - actual, RESERVATION_SETTLED);
        if (!applied) {
            logger.warn("重复结算已忽略：userId={}, reservationId={}", userId, reservationId);
            return false;
        }
        if (actual > 0) {
            Long balanceAfter = getEmbeddingTokenBalance(userId);
            recordTokenConsume(userId, UserTokenRecord.TokenType.EMBEDDING, actual,
                    balanceAfter + actual, balanceAfter);
        }
        return true;
    }

    /**
     * 退还 Embedding 预留。同一次预留重复退还时只有第一次生效。
     *
     * @return {@code true} 表示本次退还有效；{@code false} 表示这次预留已经结束过。
     */
    public boolean releaseEmbeddingTokenReservation(String userId, String reservationId, long tokens) {
        if (tokens <= 0) {
            return true;
        }
        boolean applied = finishReservation(buildEmbeddingTokenKey(userId), reservationId,
                tokens, RESERVATION_RELEASED);
        if (!applied) {
            logger.warn("重复退还已忽略：userId={}, reservationId={}", userId, reservationId);
        }
        return applied;
    }

    /**
     * 原子结束一次预留：写状态标记和调整余额在同一个脚本里完成。
     *
     * @return {@code false} 表示标记已存在，这次调用不生效。
     */
    private boolean finishReservation(String balanceKey, String reservationId, long adjustment, String state) {
        Long applied = stringRedisTemplate.execute(
                RESERVATION_FINISH_SCRIPT,
                List.of(balanceKey, RESERVATION_STATE_KEY_PREFIX + reservationId),
                state,
                String.valueOf(RESERVATION_STATE_TTL.toSeconds()),
                String.valueOf(adjustment));
        return applied != null && applied == 1L;
    }

    // 用于总的请求次数记录
    private String buildQuotaKey(String scope, String userId) {
        return "quota:" + scope + ":requests:user:" + userId;
    }

    /**
     * 为用户增加总的请求次数
     */
    public void incrementUserTotalRequestCount(String scope, String userId) {
        String key = buildQuotaKey(scope, userId);
        stringRedisTemplate.opsForValue().increment(key, 1);
    }

    /**
     * 获取用户总的请求次数
     */
    public long getUserTotalRequestCount(String scope, String userId) {
        String key = buildQuotaKey(scope, userId);
        String val = stringRedisTemplate.opsForValue().get(key);
        return StringUtils.isBlank(val) ? 0 : Long.parseLong(val);
    }

    /**
     * 获取今日 Token 消耗排行榜
     *
     * @param tokenType Token 类型（LLM 或 EMBEDDING）
     * @param limit 返回数量限制
     * @return 今日消耗排行榜
     */
    public List<UserTokenRecord> getTodayTopConsumers(UserTokenRecord.TokenType tokenType, int limit) {
        LocalDate today = LocalDate.now();

        // 直接查询今日各用户的消耗统计并排序，数据库层面完成聚合和排序
        return userTokenRecordRepository.findTodayTopConsumersByTokenType(today, tokenType, Pageable.ofSize(limit));
    }

    /**
     * 为用户增加 LLM Token
     *
     * @param userId 用户 ID
     * @param tokens 增加的 token 数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void addLlmTokens(String userId, long tokens) {
        addLlmTokens(userId, tokens, "额度追加", null);
    }

    /**
     * 为用户增加 LLM Token
     *
     * @param userId 用户 ID
     * @param tokens 增加的 token 数量
     * @param reason 变动原因
     * @param remark 备注
     */
    @Transactional(rollbackFor = Exception.class)
    public void addLlmTokens(String userId, long tokens, String reason, String remark) {
        if (tokens <= 0) {
            throw new CustomException("增加的 Token 数量必须大于 0", HttpStatus.BAD_REQUEST);
        }
        String key = buildLlmTokenKey(userId);
        Long currentBalance = getLlmTokenBalance(userId);
        Long balanceAfter = safeAddTokenBalance(currentBalance, tokens);

        // 记录 Token 增加
        recordTokenIncrease(userId, UserTokenRecord.TokenType.LLM, tokens, currentBalance, balanceAfter, reason, remark);

        stringRedisTemplate.opsForValue().increment(key, tokens);
        logger.info("用户 {} 增加 LLM Token: {}, 当前余额：{}", userId, tokens, balanceAfter);
    }

    /**
     * 为用户增加 Embedding Token
     *
     * @param userId 用户 ID
     * @param tokens 增加的 token 数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void addEmbeddingTokens(String userId, long tokens) {
        addEmbeddingTokens(userId, tokens, "额度追加", null);
    }

    /**
     * 为用户增加 Embedding Token
     *
     * @param userId 用户 ID
     * @param tokens 增加的 token 数量
     * @param reason 变动原因
     * @param remark 备注
     */
    @Transactional(rollbackFor = Exception.class)
    public void addEmbeddingTokens(String userId, long tokens, String reason, String remark) {
        if (tokens <= 0) {
            throw new CustomException("增加的 Token 数量必须大于 0", HttpStatus.BAD_REQUEST);
        }

        String key = buildEmbeddingTokenKey(userId);
        Long currentBalance = getEmbeddingTokenBalance(userId);
        Long balanceAfter = safeAddTokenBalance(currentBalance, tokens);
        // 记录 Token 增加
        recordTokenIncrease(userId, UserTokenRecord.TokenType.EMBEDDING, tokens, currentBalance, balanceAfter, reason, remark);

        stringRedisTemplate.opsForValue().increment(key, tokens);
        logger.info("用户 {} 增加 Embedding Token: {}, 当前余额：{}", userId, tokens, balanceAfter);
    }

    private Long safeAddTokenBalance(Long currentBalance, long tokens) {
        try {
            return Math.addExact(currentBalance, tokens);
        } catch (ArithmeticException e) {
            throw new CustomException("Token 余额超过系统上限", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 检查用户是否有足够的 LLM Token
     *
     * @param userId 用户 ID
     * @param tokens 需要的 token 数量
     * @return true-余额充足，false-余额不足
     */
    public boolean hasEnoughLlmTokens(String userId, int tokens) {
        Long balance = getLlmTokenBalance(userId);
        return balance >= tokens;
    }

    /**
     * 构建 LLM Token Redis Key
     */
    private String buildLlmTokenKey(String userId) {
        return LLM_TOKEN_KEY_PREFIX + userId;
    }

    /**
     * 构建 Embedding Token Redis Key
     */
    private String buildEmbeddingTokenKey(String userId) {
        return EMBEDDING_TOKEN_KEY_PREFIX + userId;
    }

    private long resolveInitToken(String userId, UsageQuotaProperties.TokenQuota quota) {
        long adminInitTokens = quota.getAdminInitTokens();
        if (adminInitTokens > 0 && isAdminUser(userId)) {
            return adminInitTokens;
        }
        return quota.getInitTokens();
    }

    private boolean isAdminUser(String userId) {
        if (StringUtils.isBlank(userId)) {
            return false;
        }
        try {
            return userRepository.findById(Long.parseLong(userId))
                    .map(User::getRole)
                    .filter(User.Role.ADMIN::equals)
                    .isPresent();
        } catch (NumberFormatException e) {
            logger.debug("用户 ID 不是数字，跳过管理员初始额度判断: {}", userId);
            return false;
        }
    }

    /**
     * 记录 Token 增加
     */
    private void recordTokenIncrease(String userId,
                                     UserTokenRecord.TokenType tokenType,
                                     Long amount,
                                     Long balanceBefore,
                                     Long balanceAfter,
                                     String reason,
                                     String remark) {
        try {
            UserTokenRecord record = new UserTokenRecord();
            record.setUserId(userId);
            record.setRecordDate(LocalDate.now());
            record.setTokenType(tokenType);
            record.setChangeType(UserTokenRecord.ChangeType.INCREASE);
            record.setAmount(amount);
            record.setBalanceBefore(balanceBefore);
            record.setBalanceAfter(balanceAfter);
            record.setReason(reason == null ? "" : reason);
            record.setRemark(remark == null ? "" : remark);
            record.setRequestCount(0L);

            userTokenRecordRepository.save(record);
            logger.debug("记录 Token 增加：userId={}, type={}, amount={}", userId, tokenType, amount);
        } catch (Exception e) {
            logger.error("记录 Token 增加失败：userId={}, type={}, amount={}", userId, tokenType, amount, e);
        }
    }

    /**
     * 记录 Token 消耗（按天聚合）。
     *
     * <p>写入走 {@link UserTokenLedger}，用「UPDATE 累加」替代「先查后写」。
     */
    private void recordTokenConsume(String userId,
                                    UserTokenRecord.TokenType tokenType,
                                    Integer amount,
                                    Long balanceBefore,
                                    Long balanceAfter) {
        long tokens = amount == null ? 0L : amount;
        try {
            userTokenLedger.accumulateConsume(
                    userId,
                    LocalDate.now(),
                    tokenType.name(),
                    tokens,
                    balanceBefore == null ? 0L : balanceBefore,
                    balanceAfter == null ? 0L : balanceAfter,
                    "对话使用"
            );
            logger.debug("累加 Token 消耗记录：userId={}, type={}, amount={}", userId, tokenType, tokens);
        } catch (Exception e) {
            logger.error("记录 Token 消耗失败：userId={}, type={}, amount={}", userId, tokenType, tokens, e);
        }
    }

    /**
     * 分页查询用户 Token 记录
     *
     * @param userId 用户 ID
     * @param page 页码（从 0 开始）
     * @param size 每页大小
     * @return 分页结果
     */
    public Page<UserTokenRecord> getUserTokenRecords(String userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "recordDate"));
        Page<UserTokenRecord> recordPage = userTokenRecordRepository.findByUserIdOrderByRecordDateDesc(userId, pageable);
        return recordPage;
    }

    public Long getUserLlmTotalIncreaseTokens(String userId) {
        return getUserTotalIncreaseTokens(userId, UserTokenRecord.TokenType.LLM);
    }

    public Long getUserEmbeddingTotalIncreaseTokens(String userId) {
        return getUserTotalIncreaseTokens(userId, UserTokenRecord.TokenType.EMBEDDING);
    }

    /**
     * 获取用户总 Token 增加量
     *
     * @param userId 用户 ID
     * @param tokenType Token 类型
     * @return 总 Token 增加量
     */
    public Long getUserTotalIncreaseTokens(String userId, UserTokenRecord.TokenType tokenType) {
        return userTokenRecordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                userId, tokenType, UserTokenRecord.ChangeType.INCREASE
        );
    }


    // 用户的每天对话次数
    public long getUserDailyChatCount(String userId, LocalDate day) {
        Optional<UserDailyChatCount> record = userDailyChatCountRepository.findByUserIdAndRecordDate(userId, day);
        return record.map(UserDailyChatCount::getChatRequestCount).orElse(0L);
    }

    /**
     * 累加当天的对话次数。
     *
     * <p>并发首写同一行时数据库只放行一个事务，其余事务重试后命中 UPDATE。
     */
    public void updateUserDailyChatCount(String userId, LocalDate day) {
        userTokenLedger.incrementChatRequest(userId, day);
    }


    /**
     * 根据类型查询每日统计数据
     */
    public List<DailyUsageStat> getDailyStatsByType(
            LocalDate startDate,
            LocalDate endDate,
            UserTokenRecord.TokenType tokenType
    ) {
        return userTokenRecordRepository.findDailyUsageStatsByDateRangeAndTokenType(
                startDate, endDate, tokenType
        );
    }

    public List<DailyReqCountStat> getDailyReqCountStats(
            LocalDate startDate,
            LocalDate endDate
    ) {
        return userDailyChatCountRepository.findDailyChatCountStatsByDateRange(startDate, endDate);
    }

    /**
     * 获取 Token 余额不足的用户列表（用于预警）- 使用 JPQL 子查询
     *
     * @param tokenType Token 类型
     * @param minBalance 最低余额阈值
     * @return 需要预警的用户列表
     */
    public List<UserTokenRecord> getLowBalanceUsers(UserTokenRecord.TokenType tokenType,
                                                    long minBalance) {
        // 直接使用 Repository 的 JPQL 子查询方法
        return userTokenRecordRepository.findUsersWithLowBalance(tokenType, minBalance);
    }
}
