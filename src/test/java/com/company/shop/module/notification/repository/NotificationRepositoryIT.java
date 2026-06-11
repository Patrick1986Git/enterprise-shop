package com.company.shop.module.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class NotificationRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanNotifications() {
        jdbcTemplate.update("DELETE FROM notifications");
    }

    @Test
    void save_shouldPersistPendingNotification() {
        UUID sourceEventId = UUID.randomUUID();
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed: " + sourceEventId,
                "Your order has been placed.",
                sourceEventId);

        Notification savedNotification = notificationRepository.saveAndFlush(notification);

        assertThat(savedNotification.getId()).isNotNull();
        assertThat(savedNotification.getType()).isEqualTo("ORDER_PLACED_EMAIL");
        assertThat(savedNotification.getRecipient()).isEqualTo("customer@example.com");
        assertThat(savedNotification.getSubject()).startsWith("Order placed:");
        assertThat(savedNotification.getBody()).isEqualTo("Your order has been placed.");
        assertThat(savedNotification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(savedNotification.getSourceEventId()).isEqualTo(sourceEventId);
        assertThat(savedNotification.getCreatedAt()).isNotNull();
        assertThat(savedNotification.getSentAt()).isNull();
        assertThat(savedNotification.getAttempts()).isZero();
        assertThat(savedNotification.getLastError()).isNull();
        assertThat(savedNotification.getLastAttemptAt()).isNull();
        assertThat(savedNotification.getNextAttemptAt()).isNull();
    }

    @Test
    void saveAndLoad_shouldPreserveLastAttemptAtAfterLifecycleTransition() {
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        notification.markSent();
        Instant lastAttemptAt = notification.getLastAttemptAt();

        Notification savedNotification = notificationRepository.saveAndFlush(notification);
        entityManager.clear();

        Notification loadedNotification = notificationRepository.findById(savedNotification.getId()).orElseThrow();
        assertThat(loadedNotification.getLastAttemptAt()).isEqualTo(lastAttemptAt);
        assertThat(loadedNotification.getSentAt()).isEqualTo(lastAttemptAt);
    }

    @Test
    void findBySourceEventId_shouldReturnNotificationWhenExists() {
        UUID sourceEventId = UUID.randomUUID();
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed: " + sourceEventId,
                "Your order has been placed.",
                sourceEventId);
        Notification savedNotification = notificationRepository.saveAndFlush(notification);

        assertThat(notificationRepository.findBySourceEventId(sourceEventId))
                .contains(savedNotification);
    }

    @Test
    void saveAndFlush_shouldRejectDuplicateNonNullSourceEventId() {
        UUID sourceEventId = UUID.randomUUID();
        Notification firstNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId);
        Notification duplicateNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed again",
                "Your order has been placed again.",
                sourceEventId);
        notificationRepository.saveAndFlush(firstNotification);

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(duplicateNotification))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void saveAndFlush_shouldAllowMultipleNullSourceEventIds() {
        Notification firstNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer-one@example.com",
                "Order placed",
                "Your order has been placed.",
                null);
        Notification secondNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer-two@example.com",
                "Order placed",
                "Your order has been placed.",
                null);

        Notification firstSavedNotification = notificationRepository.saveAndFlush(firstNotification);
        Notification secondSavedNotification = notificationRepository.saveAndFlush(secondNotification);

        assertThat(firstSavedNotification.getId()).isNotNull();
        assertThat(secondSavedNotification.getId()).isNotNull();
        assertThat(firstSavedNotification.getSourceEventId()).isNull();
        assertThat(secondSavedNotification.getSourceEventId()).isNull();
    }

    @Test
    void findPendingBatchForUpdate_shouldReturnOnlyDuePendingNotificationsInDeterministicOrder() {
        UUID pendingWithoutNextAttemptId = UUID.randomUUID();
        UUID sentId = UUID.randomUUID();
        UUID pendingFutureAttemptId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID pendingPastAttemptId = UUID.randomUUID();
        Instant now = Instant.now();

        insertNotification(
                pendingWithoutNextAttemptId,
                NotificationStatus.PENDING,
                Instant.parse("2026-01-01T10:00:00Z"),
                null);
        insertNotification(
                sentId,
                NotificationStatus.SENT,
                Instant.parse("2026-01-01T10:01:00Z"),
                null);
        insertNotification(
                pendingFutureAttemptId,
                NotificationStatus.PENDING,
                Instant.parse("2026-01-01T10:02:00Z"),
                now.plusSeconds(3600));
        insertNotification(
                failedId,
                NotificationStatus.FAILED,
                Instant.parse("2026-01-01T10:03:00Z"),
                null);
        insertNotification(
                pendingPastAttemptId,
                NotificationStatus.PENDING,
                Instant.parse("2026-01-01T10:04:00Z"),
                now.minusSeconds(3600));

        List<Notification> pendingNotifications = notificationRepository.findPendingBatchForUpdate(10);

        assertThat(pendingNotifications)
                .extracting(Notification::getId)
                .containsExactly(pendingWithoutNextAttemptId, pendingPastAttemptId)
                .doesNotContain(sentId, failedId, pendingFutureAttemptId);
        assertThat(pendingNotifications)
                .extracting(Notification::getStatus)
                .containsOnly(NotificationStatus.PENDING);
    }

    @Test
    void countDuePending_shouldCountOnlyPendingNotificationsDueNow() {
        Instant now = Instant.now();
        UUID pendingWithoutNextAttemptId = UUID.randomUUID();
        UUID pendingPastAttemptId = UUID.randomUUID();
        UUID pendingFutureAttemptId = UUID.randomUUID();
        UUID sentId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();

        insertNotification(pendingWithoutNextAttemptId, NotificationStatus.PENDING, now.minusSeconds(300), null);
        insertNotification(
                pendingPastAttemptId,
                NotificationStatus.PENDING,
                now.minusSeconds(240),
                now.minusSeconds(60));
        insertNotification(
                pendingFutureAttemptId,
                NotificationStatus.PENDING,
                now.minusSeconds(180),
                now.plusSeconds(60));
        insertNotification(sentId, NotificationStatus.SENT, now.minusSeconds(120), null);
        insertNotification(failedId, NotificationStatus.FAILED, now.minusSeconds(60), null);

        long duePendingCount = notificationRepository.countDuePending(now);

        assertThat(duePendingCount).isEqualTo(2L);
    }

    @Test
    void countScheduledPending_shouldCountOnlyPendingNotificationsScheduledForFutureRetry() {
        Instant now = Instant.now();
        UUID pendingFutureAttemptId = UUID.randomUUID();
        UUID pendingWithoutNextAttemptId = UUID.randomUUID();
        UUID pendingPastAttemptId = UUID.randomUUID();
        UUID sentId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();

        insertNotification(
                pendingFutureAttemptId,
                NotificationStatus.PENDING,
                now.minusSeconds(300),
                now.plusSeconds(60));
        insertNotification(pendingWithoutNextAttemptId, NotificationStatus.PENDING, now.minusSeconds(240), null);
        insertNotification(
                pendingPastAttemptId,
                NotificationStatus.PENDING,
                now.minusSeconds(180),
                now.minusSeconds(60));
        insertNotification(sentId, NotificationStatus.SENT, now.minusSeconds(120), now.plusSeconds(60));
        insertNotification(failedId, NotificationStatus.FAILED, now.minusSeconds(60), now.plusSeconds(60));

        long scheduledPendingCount = notificationRepository.countScheduledPending(now);

        assertThat(scheduledPendingCount).isEqualTo(1L);
    }

    @Test
    void findPendingBatchForUpdate_shouldRespectBatchSize() {
        UUID firstPendingId = UUID.randomUUID();
        UUID secondPendingId = UUID.randomUUID();

        insertNotification(firstPendingId, NotificationStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"), null);
        insertNotification(secondPendingId, NotificationStatus.PENDING, Instant.parse("2026-01-01T10:01:00Z"), null);

        List<Notification> pendingNotifications = notificationRepository.findPendingBatchForUpdate(1);

        assertThat(pendingNotifications)
                .extracting(Notification::getId)
                .containsExactly(firstPendingId);
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByStatusWhenRecipientIsNull() {
        Notification pendingNotification = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "pending@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));
        Notification sentNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "sent@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        sentNotification.markSent();
        notificationRepository.saveAndFlush(sentNotification);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationStatus.PENDING, null, null),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(pendingNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByTypeWhenRecipientIsNull() {
        Notification orderNotification = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));
        notificationRepository.saveAndFlush(Notification.pending(
                "PASSWORD_RESET_EMAIL",
                "customer@example.com",
                "Password reset",
                "Reset your password.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(null, "ORDER_PLACED_EMAIL", null),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(orderNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByRecipientContainsIgnoreCase() {
        Notification customerNotification = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "Important.Customer@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));
        notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "other@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(null, null, "CUSTOMER"),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(customerNotification.getId());
    }

    @Test
    void insert_shouldUseDatabaseDefaultsForStatusCreatedAtAttemptsAndLastAttemptAtAndNextAttemptAt() {
        UUID notificationId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO notifications (id, type, recipient, subject, body)
                VALUES (?, ?, ?, ?, ?)
                """,
                notificationId,
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.");

        Map<String, Object> defaults = jdbcTemplate.queryForMap(
                """
                        SELECT status, created_at, sent_at, attempts, last_error, last_attempt_at, next_attempt_at
                        FROM notifications
                        WHERE id = ?
                        """,
                notificationId);

        assertThat(defaults)
                .containsEntry("status", NotificationStatus.PENDING.name())
                .containsEntry("sent_at", null)
                .containsEntry("attempts", 0)
                .containsEntry("last_error", null)
                .containsEntry("last_attempt_at", null)
                .containsEntry("next_attempt_at", null);
        assertThat(defaults.get("created_at")).isNotNull();
    }

    private void insertNotification(
            UUID notificationId,
            NotificationStatus status,
            Instant createdAt,
            Instant nextAttemptAt) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO notifications (
                        id, type, recipient, subject, body, status, created_at, sent_at, last_error, next_attempt_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?, CAST(? AS timestamptz)
                    )
                    """);
            statement.setObject(1, notificationId);
            statement.setString(2, "ORDER_PLACED_EMAIL");
            statement.setString(3, "customer@example.com");
            statement.setString(4, "Order placed");
            statement.setString(5, "Your order has been placed.");
            statement.setString(6, status.name());
            statement.setString(7, createdAt.toString());
            statement.setString(8, status == NotificationStatus.SENT ? createdAt.plusSeconds(60).toString() : null);
            statement.setString(9, status == NotificationStatus.FAILED ? "delivery failed" : null);
            statement.setString(10, nextAttemptAt == null ? null : nextAttemptAt.toString());
            return statement;
        });
    }
}
