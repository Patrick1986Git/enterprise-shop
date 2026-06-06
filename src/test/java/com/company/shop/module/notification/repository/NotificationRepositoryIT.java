package com.company.shop.module.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class NotificationRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        assertThat(savedNotification.getLastError()).isNull();
    }

    @Test
    void findPendingBatchForUpdate_shouldReturnPendingNotificationsOrderedByCreatedAt() {
        UUID earliestPendingId = UUID.randomUUID();
        UUID sentId = UUID.randomUUID();
        UUID latestPendingId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID middlePendingId = UUID.randomUUID();

        insertNotification(earliestPendingId, NotificationStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"));
        insertNotification(sentId, NotificationStatus.SENT, Instant.parse("2026-01-01T10:01:00Z"));
        insertNotification(latestPendingId, NotificationStatus.PENDING, Instant.parse("2026-01-01T10:04:00Z"));
        insertNotification(failedId, NotificationStatus.FAILED, Instant.parse("2026-01-01T10:02:00Z"));
        insertNotification(middlePendingId, NotificationStatus.PENDING, Instant.parse("2026-01-01T10:03:00Z"));

        List<Notification> pendingNotifications = notificationRepository.findPendingBatchForUpdate(10);

        assertThat(pendingNotifications)
                .extracting(Notification::getId)
                .containsExactly(earliestPendingId, middlePendingId, latestPendingId)
                .doesNotContain(sentId, failedId);
        assertThat(pendingNotifications)
                .extracting(Notification::getStatus)
                .containsOnly(NotificationStatus.PENDING);
    }

    @Test
    void findPendingBatchForUpdate_shouldRespectBatchSize() {
        UUID firstPendingId = UUID.randomUUID();
        UUID secondPendingId = UUID.randomUUID();

        insertNotification(firstPendingId, NotificationStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"));
        insertNotification(secondPendingId, NotificationStatus.PENDING, Instant.parse("2026-01-01T10:01:00Z"));

        List<Notification> pendingNotifications = notificationRepository.findPendingBatchForUpdate(1);

        assertThat(pendingNotifications)
                .extracting(Notification::getId)
                .containsExactly(firstPendingId);
    }

    @Test
    void insert_shouldUseDatabaseDefaultsForStatusAndCreatedAt() {
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
                "SELECT status, created_at, sent_at, last_error FROM notifications WHERE id = ?",
                notificationId);

        assertThat(defaults)
                .containsEntry("status", NotificationStatus.PENDING.name())
                .containsEntry("sent_at", null)
                .containsEntry("last_error", null);
        assertThat(defaults.get("created_at")).isNotNull();
    }

    private void insertNotification(UUID notificationId, NotificationStatus status, Instant createdAt) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO notifications (
                        id, type, recipient, subject, body, status, created_at, sent_at, last_error
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?
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
            return statement;
        });
    }
}
