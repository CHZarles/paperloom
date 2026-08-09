package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.UsageQuotaProperties;
import io.github.chzarles.paperloom.exception.QuotaExceededException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageBalanceQuotaServiceTest {

    @Test
    void shouldSettleAgainstUserTokenBalanceOnly() {
        UserTokenService userTokenService = mock(UserTokenService.class);
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(new UsageQuotaProperties(), userTokenService);
        when(userTokenService.reserveLlmTokens("42", 300)).thenReturn(true);

        UsageQuotaService.TokenReservation reservation = service.reserveLlmTokens("42", 100, 200);
        service.settleReservation(reservation, 180);

        verify(userTokenService).incrementUserTotalRequestCount("llm", "42");
        verify(userTokenService).settleLlmTokenReservation("42", 300, 180);
    }

    @Test
    void shouldNotReserveWhenQuotaDisabled() {
        UserTokenService userTokenService = mock(UserTokenService.class);
        UsageQuotaProperties properties = new UsageQuotaProperties();
        properties.getLlm().setEnabled(false);
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(properties, userTokenService);

        UsageQuotaService.TokenReservation reservation = service.reserveLlmTokens("42", 100, 200);

        assertTrue(reservation.noop());
        verify(userTokenService, never()).reserveLlmTokens("42", 300);
    }

    @Test
    void shouldRejectLegacyDailyQuotaReservations() {
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(
                new UsageQuotaProperties(), mock(UserTokenService.class));
        UsageQuotaService.TokenReservation legacyReservation = new UsageQuotaService.TokenReservation(
                "llm", "42", "quota:llm:2026-07-27:user:42", "", 300, 300, 0, false, true);

        assertThrows(IllegalArgumentException.class,
                () -> service.settleReservation(legacyReservation, 180));
    }

    @Test
    void shouldFailFastWhenBalanceIsInsufficient() {
        UserTokenService userTokenService = mock(UserTokenService.class);
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(new UsageQuotaProperties(), userTokenService);
        when(userTokenService.reserveLlmTokens("42", 300)).thenReturn(false);
        when(userTokenService.getLlmTokenBalance("42")).thenReturn(120L);

        assertThrows(QuotaExceededException.class,
                () -> service.reserveLlmTokens("42", 100, 200));
    }
}
