package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.UsageQuotaProperties;
import io.github.chzarles.paperloom.model.UserTokenRecord;
import io.github.chzarles.paperloom.repository.UserDailyChatCountRepository;
import io.github.chzarles.paperloom.repository.UserRepository;
import io.github.chzarles.paperloom.repository.UserTokenRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证余额键缺失时的两条分支：按记账表重建，或按新用户发放。
 */
class UserTokenBalanceRebuildTest {

    private static final String KEY = "user:token:llm:42";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    private final UserTokenRecordRepository recordRepository = mock(UserTokenRecordRepository.class);

    private final UsageQuotaProperties properties = new UsageQuotaProperties();

    private UserTokenService service;

    @BeforeEach
    void setUp() {
        properties.getLlm().setInitTokens(1000);
        when(redis.opsForValue()).thenReturn(ops);
        service = new UserTokenService(
                redis,
                properties,
                recordRepository,
                mock(UserDailyChatCountRepository.class),
                mock(UserRepository.class),
                mock(UserTokenLedger.class));
    }

    @Test
    void rebuildsBalanceFromLedgerWhenKeyIsMissing() {
        when(ops.get(KEY)).thenReturn(null, "600");
        when(recordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                "42", UserTokenRecord.TokenType.LLM, UserTokenRecord.ChangeType.INCREASE)).thenReturn(1000L);
        when(recordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                "42", UserTokenRecord.TokenType.LLM, UserTokenRecord.ChangeType.CONSUME)).thenReturn(400L);

        assertEquals(600L, service.getLlmTokenBalance("42"));
        verify(ops).setIfAbsent(KEY, "600");
        verify(recordRepository, never()).save(any());
    }

    @Test
    void seedsInitialTokensWhenLedgerHasNoIncreaseRow() {
        when(ops.get(KEY)).thenReturn(null);
        when(recordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                eq("42"), eq(UserTokenRecord.TokenType.LLM), eq(UserTokenRecord.ChangeType.INCREASE)))
                .thenReturn(0L);
        when(ops.setIfAbsent(KEY, "1000")).thenReturn(true);

        assertEquals(1000L, service.getLlmTokenBalance("42"));
        verify(recordRepository).save(any(UserTokenRecord.class));
    }

    @Test
    void clampsRebuiltBalanceAtZero() {
        when(ops.get(KEY)).thenReturn(null, "0");
        when(recordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                "42", UserTokenRecord.TokenType.LLM, UserTokenRecord.ChangeType.INCREASE)).thenReturn(100L);
        when(recordRepository.sumAmountByUserIdAndTokenTypeAndChangeType(
                "42", UserTokenRecord.TokenType.LLM, UserTokenRecord.ChangeType.CONSUME)).thenReturn(300L);

        assertEquals(0L, service.getLlmTokenBalance("42"));
        verify(ops).setIfAbsent(KEY, "0");
    }
}
