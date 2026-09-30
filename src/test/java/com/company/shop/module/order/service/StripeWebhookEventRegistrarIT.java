package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.order.entity.StripeWebhookEvent;
import com.company.shop.module.order.repository.StripeWebhookEventRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest
@ActiveProfiles("test")
class StripeWebhookEventRegistrarIT extends PostgresContainerSupport {

    @Autowired
    private StripeWebhookEventRegistrar registrar;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StripeWebhookEventRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void register_shouldUseDatabaseUtcObservationAndKeepOriginalTimestampOnDuplicate() {
        String eventId = uniqueEventId("timestamp");
        LocalDateTime before = databaseUtcNow();

        assertThat(registerInTransaction(eventId)).isTrue();

        LocalDateTime originalTimestamp = processedAt(eventId);
        LocalDateTime after = databaseUtcNow();
        assertThat(originalTimestamp).isBetween(before, after);

        StripeWebhookEvent persistedEvent = repository.findById(rowId(eventId)).orElseThrow();
        assertThat(persistedEvent.getStripeEventId()).isEqualTo(eventId);
        assertThat(persistedEvent.getEventType()).isEqualTo("payment_intent.succeeded");
        assertThat(persistedEvent.getProcessedAt()).isEqualTo(originalTimestamp);

        assertThat(registerInTransaction(eventId)).isFalse();
        assertThat(processedAt(eventId)).isEqualTo(originalTimestamp);
        assertThat(rowCount(eventId)).isOne();
    }

    @Test
    void register_shouldRollBackBarrierAndAllowRetry() {
        String eventId = uniqueEventId("retry");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            assertThat(registrar.register(eventId, "payment_intent.succeeded")).isTrue();
            throw new SimulatedProcessingFailure();
        })).isInstanceOf(SimulatedProcessingFailure.class);

        assertThat(rowCount(eventId)).isZero();
        assertThat(registerInTransaction(eventId)).isTrue();
        assertThat(rowCount(eventId)).isOne();
    }

    @Test
    void register_shouldCommitExactlyOneBarrierForConcurrentDuplicates() throws Exception {
        String eventId = uniqueEventId("concurrent");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> registerAfterBarrier(eventId, ready, start));
            var second = executor.submit(() -> registerAfterBarrier(eventId, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        assertThat(rowCount(eventId)).isOne();
    }

    private boolean registerAfterBarrier(String eventId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to start concurrent registration");
        }
        return registerInTransaction(eventId);
    }

    private boolean registerInTransaction(String eventId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(
                status -> registrar.register(eventId, "payment_intent.succeeded")));
    }

    private LocalDateTime databaseUtcNow() {
        return jdbcTemplate.queryForObject(
                "SELECT clock_timestamp() AT TIME ZONE 'UTC'", LocalDateTime.class);
    }

    private LocalDateTime processedAt(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT processed_at FROM stripe_webhook_events WHERE stripe_event_id = ?",
                LocalDateTime.class,
                eventId);
    }

    private UUID rowId(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM stripe_webhook_events WHERE stripe_event_id = ?",
                UUID.class,
                eventId);
    }

    private long rowCount(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stripe_webhook_events WHERE stripe_event_id = ?",
                Long.class,
                eventId);
    }

    private String uniqueEventId(String scenario) {
        return "evt_registrar_" + scenario + "_" + UUID.randomUUID();
    }

    private static final class SimulatedProcessingFailure extends RuntimeException {
    }
}
