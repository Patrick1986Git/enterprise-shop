package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationClaimServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final UUID WORK_ID = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000072");
    @Mock ReservationExpirationWorkRepository repository;
    private ReservationExpirationProperties properties;
    private ReservationExpirationClaimService service;

    @BeforeEach
    void setUp() {
        properties = new ReservationExpirationProperties();
        service = new ReservationExpirationClaimService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void retry_shouldReportExhaustionAndPersistExactFailureTime() {
        ReservationExpirationWork work = new ReservationExpirationWork(ORDER_ID, NOW.minusSeconds(60));
        UUID token = work.claim(NOW.minusSeconds(30), NOW.plusSeconds(30));
        ReservationExpirationClaim claim = new ReservationExpirationClaim(WORK_ID, ORDER_ID, token);
        properties.setMaxAttempts(1);
        when(repository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));

        boolean exhausted = service.retry(claim, "provider unavailable");

        assertThat(exhausted).isTrue();
        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
        assertThat(work.getFailedAt()).isEqualTo(NOW);
        assertThat(work.getNextAttemptAt()).isEqualTo(NOW.plus(properties.retryDelay()));
    }

    @Test
    void retry_shouldReportScheduledRetryBeforeBudgetIsExhausted() {
        ReservationExpirationWork work = new ReservationExpirationWork(ORDER_ID, NOW.minusSeconds(60));
        UUID token = work.claim(NOW.minusSeconds(30), NOW.plusSeconds(30));
        ReservationExpirationClaim claim = new ReservationExpirationClaim(WORK_ID, ORDER_ID, token);
        when(repository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));

        assertThat(service.retry(claim, "provider unavailable")).isFalse();
        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.PENDING);
    }

    @Test
    void retry_shouldReturnFalseWhenWorkWasAlreadyRemoved() {
        ReservationExpirationClaim claim = new ReservationExpirationClaim(WORK_ID, ORDER_ID, UUID.randomUUID());
        when(repository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.empty());

        assertThat(service.retry(claim, "provider unavailable")).isFalse();
    }
}
