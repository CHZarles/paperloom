package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.model.UserTokenRecord;
import io.github.chzarles.paperloom.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageBalanceDashboardServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UsageQuotaService usageQuotaService;

    @Mock
    private UserTokenService userTokenService;

    @Test
    void shouldKeepUsageMonitorAvailableWhenTokenRecordsContainLegacyUserIds() {
        UsageBalanceDashboardService dashboardService =
                new UsageBalanceDashboardService(userRepository, usageQuotaService, userTokenService);
        UserTokenRecord llmConsumer = tokenRecord("legacy-user", UserTokenRecord.TokenType.LLM, 1200L, 8800L, 3L);

        when(usageQuotaService.getDailyAggregates(List.of(), 7)).thenReturn(List.of(
                new UsageQuotaService.DailyUsageAggregate("2026-07-13", 0, 1200, 3, 0, 0)
        ));
        when(userTokenService.getTodayTopConsumers(UserTokenRecord.TokenType.LLM, 5)).thenReturn(List.of(llmConsumer));
        when(userTokenService.getTodayTopConsumers(UserTokenRecord.TokenType.EMBEDDING, 5)).thenReturn(List.of());
        when(userTokenService.getLowBalanceUsers(UserTokenRecord.TokenType.LLM, 9300L)).thenReturn(List.of(llmConsumer));
        when(userTokenService.getLowBalanceUsers(UserTokenRecord.TokenType.EMBEDDING, 9300L)).thenReturn(List.of());
        when(userTokenService.getUserLlmTotalIncreaseTokens("legacy-user")).thenReturn(10_000L);

        UsageDashboardService.UsageOverview overview =
                assertDoesNotThrow(() -> dashboardService.buildOverview(7));

        assertEquals("Unknown User", overview.llmRankings().get(0).username());
        assertEquals("warning", overview.alerts().get(0).level());
        assertEquals(1200L, overview.alerts().get(0).usedTokens());
        assertEquals(10_000L, overview.alerts().get(0).limitTokens());
        assertEquals(8800L, overview.alerts().get(0).remainingTokens());
    }

    private UserTokenRecord tokenRecord(String userId,
                                        UserTokenRecord.TokenType tokenType,
                                        long amount,
                                        long balanceAfter,
                                        long requestCount) {
        UserTokenRecord record = new UserTokenRecord();
        record.setUserId(userId);
        record.setTokenType(tokenType);
        record.setChangeType(UserTokenRecord.ChangeType.CONSUME);
        record.setAmount(amount);
        record.setBalanceBefore(balanceAfter + amount);
        record.setBalanceAfter(balanceAfter);
        record.setRequestCount(requestCount);
        return record;
    }
}
