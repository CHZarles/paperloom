package io.github.chzarles.paperloom.service;

import io.github.chzarles.paperloom.config.UsageQuotaProperties;
import io.github.chzarles.paperloom.exception.QuotaExceededException;
import io.github.chzarles.paperloom.model.User;
import io.github.chzarles.paperloom.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        UsageBalanceQuotaService service = service(new UsageQuotaProperties(), userTokenService);
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
        UsageBalanceQuotaService service = service(properties, userTokenService);

        UsageQuotaService.TokenReservation reservation = service.reserveLlmTokens("42", 100, 200);

        assertTrue(reservation.noop());
        verify(userTokenService, never()).reserveLlmTokens("42", 300);
    }

    @Test
    void shouldRejectLegacyDailyQuotaReservations() {
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(
                new UsageQuotaProperties(), mock(UserTokenService.class), mock(UserRepository.class));
        UsageQuotaService.TokenReservation legacyReservation = new UsageQuotaService.TokenReservation(
                "llm", "42", "quota:llm:2026-07-27:user:42", "", 300, 300, 0, false, true);

        assertThrows(IllegalArgumentException.class,
                () -> service.settleReservation(legacyReservation, 180));
    }

    @Test
    void shouldFailFastWhenBalanceIsInsufficient() {
        UserTokenService userTokenService = mock(UserTokenService.class);
        UsageBalanceQuotaService service = service(new UsageQuotaProperties(), userTokenService);
        when(userTokenService.reserveLlmTokens("42", 300)).thenReturn(false);
        when(userTokenService.getLlmTokenBalance("42")).thenReturn(120L);

        assertThrows(QuotaExceededException.class,
                () -> service.reserveLlmTokens("42", 100, 200));
    }

    @Test
    void shouldReserveGuestLlmTokensFromSharedPool() {
        UserTokenService userTokenService = mock(UserTokenService.class);
        UserRepository userRepository = mock(UserRepository.class);
        User guest = new User();
        guest.setRole(User.Role.GUEST);
        when(userRepository.findById(42L)).thenReturn(Optional.of(guest));
        when(userTokenService.reserveLlmTokens("guest-pool", 300)).thenReturn(true);
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(
                new UsageQuotaProperties(), userTokenService, userRepository);

        UsageQuotaService.TokenReservation reservation = service.reserveLlmTokens("42", 100, 200);
        service.settleReservation(reservation, 180);

        verify(userTokenService).reserveLlmTokens("guest-pool", 300);
        verify(userTokenService).settleLlmTokenReservation("guest-pool", 300, 180);
    }

    @Test
    void shouldConsumeGuestEmbeddingTokensFromSharedPool() {
        UserTokenService userTokenService = mock(UserTokenService.class);
        UserRepository userRepository = mock(UserRepository.class);
        User guest = new User();
        guest.setRole(User.Role.GUEST);
        when(userRepository.findById(42L)).thenReturn(Optional.of(guest));
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(
                new UsageQuotaProperties(), userTokenService, userRepository);
        int estimatedTokens = service.estimateEmbeddingTokens(List.of("hello"));
        when(userTokenService.reserveEmbeddingTokens("guest-pool", estimatedTokens)).thenReturn(true);

        UsageQuotaService.TokenReservation reservation =
                service.reserveEmbeddingTokens("42", List.of("hello"));
        service.settleReservation(reservation, 2);

        verify(userTokenService).reserveEmbeddingTokens("guest-pool", estimatedTokens);
        verify(userTokenService).settleEmbeddingTokenReservation("guest-pool", estimatedTokens, 2);
    }

    @Test
    void shouldShowSharedBalanceUnderOriginalGuestId() {
        UserTokenService userTokenService = mock(UserTokenService.class);
        UserRepository userRepository = mock(UserRepository.class);
        User guest = new User();
        guest.setId(42L);
        guest.setRole(User.Role.GUEST);
        when(userRepository.findAllById(List.of(42L))).thenReturn(List.of(guest));
        when(userTokenService.getLlmTokenBalance("guest-pool")).thenReturn(80L);
        when(userTokenService.getUserLlmTotalIncreaseTokens("guest-pool")).thenReturn(100L);
        when(userTokenService.getEmbeddingTokenBalance("guest-pool")).thenReturn(60L);
        when(userTokenService.getUserEmbeddingTotalIncreaseTokens("guest-pool")).thenReturn(100L);
        UsageBalanceQuotaService service = new UsageBalanceQuotaService(
                new UsageQuotaProperties(), userTokenService, userRepository);

        UsageQuotaService.UserUsageSnapshot snapshot = service.getSnapshots(List.of("42")).get("42");

        assertEquals(80L, snapshot.llm().remainingTokens());
        assertEquals(60L, snapshot.embedding().remainingTokens());
        verify(userTokenService).getUserDailyChatCount("42", LocalDate.now());
    }

    private UsageBalanceQuotaService service(UsageQuotaProperties properties,
                                             UserTokenService userTokenService) {
        return new UsageBalanceQuotaService(properties, userTokenService, mock(UserRepository.class));
    }
}
