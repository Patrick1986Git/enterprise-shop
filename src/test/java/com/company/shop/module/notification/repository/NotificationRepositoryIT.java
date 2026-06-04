package com.company.shop.module.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
}
