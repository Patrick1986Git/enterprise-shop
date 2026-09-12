package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.entity.StripePaymentConflict;
import com.company.shop.module.order.entity.StripePaymentConflictDispositionType;
import com.company.shop.module.order.entity.StripePaymentConflictReason;
import com.company.shop.module.order.repository.StripePaymentConflictDispositionRepository;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.security.CurrentUserProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class StripePaymentConflictDispositionConcurrencyIT extends PostgresContainerSupport {
    @Autowired StripePaymentConflictDispositionCommandService service;
    @Autowired StripePaymentConflictRepository conflictRepository;
    @Autowired StripePaymentConflictDispositionRepository dispositionRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean CurrentUserProvider currentUserProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE stripe_payment_conflict_dispositions, stripe_payment_conflicts CASCADE");
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");
    }

    @Test
    void append_shouldPreserveConcurrentValidActionsAndOriginalEvidence() throws Exception {
        StripePaymentConflict conflict = conflictRepository.saveAndFlush(new StripePaymentConflict(
                UUID.randomUUID(), UUID.randomUUID(), "evt_" + UUID.randomUUID(), "pi_" + UUID.randomUUID(),
                "payment_intent.succeeded", OrderStatus.CANCELLED, PaymentStatus.FAILED,
                StripePaymentConflictReason.TERMINAL_STATE_CONTRADICTION,
                Instant.parse("2026-09-12T10:00:00Z")));
        CyclicBarrier start = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> acknowledgement = executor.submit(() -> appendAfterBarrier(
                    start, conflict.getId(), StripePaymentConflictDispositionType.ACKNOWLEDGED));
            Future<?> escalation = executor.submit(() -> appendAfterBarrier(
                    start, conflict.getId(), StripePaymentConflictDispositionType.ESCALATED));
            start.await(5, TimeUnit.SECONDS);
            acknowledgement.get(5, TimeUnit.SECONDS);
            escalation.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(dispositionRepository.findAll())
                .extracting(disposition -> disposition.getActionType())
                .containsExactlyInAnyOrder(
                        StripePaymentConflictDispositionType.ACKNOWLEDGED,
                        StripePaymentConflictDispositionType.ESCALATED);
        StripePaymentConflict unchanged = conflictRepository.findById(conflict.getId()).orElseThrow();
        assertThat(unchanged.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(unchanged.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(unchanged.getStripeEventId()).isEqualTo(conflict.getStripeEventId());
    }

    private void appendAfterBarrier(
            CyclicBarrier start, UUID conflictId, StripePaymentConflictDispositionType actionType) {
        try {
            start.await(5, TimeUnit.SECONDS);
            service.append(conflictId, actionType);
        } catch (Exception exception) {
            throw new AssertionError("concurrent disposition failed", exception);
        }
    }
}
