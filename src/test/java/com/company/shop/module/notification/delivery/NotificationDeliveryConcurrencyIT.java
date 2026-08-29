package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.repository.NotificationRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest
@ActiveProfiles("test")
class NotificationDeliveryConcurrencyIT extends PostgresContainerSupport {

    @Autowired
    private NotificationRepository repository;
    @Autowired
    private NotificationDeliveryProcessor processor;
    @Autowired
    private NotificationDeliveryTransactionalWorker transactionalWorker;
    @Autowired
    private NotificationDeliveryProperties properties;
    @Autowired
    private BlockingSender sender;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        sender.reset();
        jdbcTemplate.update("DELETE FROM notification_admin_action_logs");
        jdbcTemplate.update("DELETE FROM notifications");
    }

    @Test
    void blockedExternalSend_shouldExposeCommittedClaimAndAllowUnrelatedProgress() throws Exception {
        Notification x = save("x@example.com");
        Notification y = save("y@example.com");
        jdbcTemplate.update("UPDATE notifications SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(2)), x.getId());
        jdbcTemplate.update("UPDATE notifications SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), y.getId());
        sender.blockRecipient(x.getRecipient());

        CompletableFuture<NotificationDeliveryResult> workerA = CompletableFuture.supplyAsync(
                () -> processor.processPendingBatch(1));
        assertThat(sender.awaitBlocked()).isTrue();

        Map<String, Object> durableX = row(x.getId());
        assertThat(durableX.get("status")).isEqualTo("PROCESSING");
        assertThat(durableX.get("claim_token")).isNotNull();
        assertThat(durableX.get("claim_expires_at")).isNotNull();

        NotificationDeliveryResult workerB = processor.processPendingBatch(1);
        assertThat(workerB.sentCount()).isEqualTo(1);
        assertThat(row(y.getId()).get("status")).isEqualTo("SENT");
        assertThat(row(x.getId()).get("status")).isEqualTo("PROCESSING");
        assertThat(transactionalWorker.claimBatch(1)).isEmpty();

        sender.release();
        assertThat(workerA.get(10, TimeUnit.SECONDS).sentCount()).isEqualTo(1);
        assertThat(row(x.getId()).get("status")).isEqualTo("SENT");
    }

    @Test
    void expiredClaim_shouldBeReownedAndRejectBothStaleFinalizers() {
        Notification notification = save("recover@example.com");
        ClaimedNotification oldClaim = transactionalWorker.claimBatch(1).getFirst();
        jdbcTemplate.update("UPDATE notifications SET claim_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), notification.getId());

        ClaimedNotification newClaim = transactionalWorker.claimBatch(1).getFirst();

        assertThat(newClaim.token()).isNotEqualTo(oldClaim.token());
        Map<String, Object> recovered = row(notification.getId());
        assertThat(recovered.get("status")).isEqualTo("PROCESSING");
        assertThat(recovered.get("claim_token")).isEqualTo(newClaim.token());
        assertThat(((Number) recovered.get("attempts")).intValue()).isEqualTo(2);
        assertThat(((Timestamp) recovered.get("claim_expires_at")).toInstant()).isAfter(Instant.now());

        assertThat(transactionalWorker.finalizeSuccess(notification.getId(), oldClaim.token())).isFalse();
        assertThat(transactionalWorker.finalizeFailure(notification.getId(), oldClaim.token(), "stale")).isFalse();
        Map<String, Object> afterStaleFinalizers = row(notification.getId());
        assertThat(afterStaleFinalizers.get("status")).isEqualTo("PROCESSING");
        assertThat(afterStaleFinalizers.get("claim_token")).isEqualTo(newClaim.token());
        assertThat(afterStaleFinalizers.get("last_error")).isNull();

        assertThat(transactionalWorker.finalizeSuccess(notification.getId(), newClaim.token())).isTrue();
        Map<String, Object> finalized = row(notification.getId());
        assertThat(finalized.get("status")).isEqualTo("SENT");
        assertThat(finalized.get("claim_token")).isNull();
        assertThat(finalized.get("claim_expires_at")).isNull();
    }

    @Test
    void exhaustedExpiredClaim_shouldBecomeFailedWithoutAnotherSend() {
        Notification notification = save("exhausted@example.com");
        transactionalWorker.claimBatch(1);
        jdbcTemplate.update("UPDATE notifications SET attempts = ?, claim_expires_at = ? WHERE id = ?",
                properties.maxAttempts(), Timestamp.from(Instant.now().minusSeconds(1)), notification.getId());

        assertThat(transactionalWorker.claimBatch(1)).isEmpty();

        Map<String, Object> failed = row(notification.getId());
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("claim_token")).isNull();
        assertThat(failed.get("claim_expires_at")).isNull();
        assertThat(sender.sentRecipients()).isEmpty();
    }

    private Notification save(String recipient) {
        return repository.saveAndFlush(Notification.pending("ORDER_PLACED_EMAIL", recipient, "Order placed",
                "Your order has been placed.", UUID.randomUUID()));
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("""
                SELECT status, claim_token, claim_expires_at, attempts, last_error
                FROM notifications WHERE id = ?
                """, id);
    }

    @TestConfiguration
    static class SenderConfiguration {
        @Bean
        @Primary
        BlockingSender blockingSender() {
            return new BlockingSender();
        }
    }

    static final class BlockingSender implements NotificationSender {
        private volatile String blockedRecipient;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private final java.util.concurrent.CopyOnWriteArrayList<String> sentRecipients =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        void reset() {
            blockedRecipient = null;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            sentRecipients.clear();
        }

        void blockRecipient(String recipient) {
            blockedRecipient = recipient;
        }

        boolean awaitBlocked() throws InterruptedException {
            return entered.await(10, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        List<String> sentRecipients() {
            return List.copyOf(sentRecipients);
        }

        @Override
        public void send(Notification notification) {
            if (notification.getRecipient().equals(blockedRecipient)) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release deterministic sender");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to release deterministic sender", ex);
                }
            }
            sentRecipients.add(notification.getRecipient());
        }
    }
}
