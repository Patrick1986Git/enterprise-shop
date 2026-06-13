package com.company.shop.module.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.notification.entity.NotificationAdminActionLog;
import com.company.shop.module.notification.entity.NotificationAdminActionType;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class NotificationAdminActionLogRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private NotificationAdminActionLogRepository notificationAdminActionLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanNotificationAdminActionLogs() {
        jdbcTemplate.update("DELETE FROM notification_admin_action_logs");
    }

    @Test
    void saveAndLoad_shouldPersistRequeueActionLog() {
        UUID notificationId = UUID.randomUUID();
        NotificationAdminActionLog log = NotificationAdminActionLog.requeue(notificationId, "admin@example.com");

        NotificationAdminActionLog savedLog = notificationAdminActionLogRepository.saveAndFlush(log);
        entityManager.clear();

        NotificationAdminActionLog loadedLog = notificationAdminActionLogRepository.findById(savedLog.getId()).orElseThrow();
        assertThat(loadedLog.getNotificationId()).isEqualTo(notificationId);
        assertThat(loadedLog.getActionType()).isEqualTo(NotificationAdminActionType.REQUEUE);
        assertThat(loadedLog.getActorEmail()).isEqualTo("admin@example.com");
        assertThat(loadedLog.getCreatedAt()).isNotNull();
        assertThat(loadedLog.getDetails()).isNull();
    }

    @Test
    void findByNotificationIdOrderByCreatedAtDesc_shouldReturnSelectedNotificationLogsNewestFirst() {
        UUID selectedNotificationId = UUID.randomUUID();
        UUID otherNotificationId = UUID.randomUUID();
        NotificationAdminActionLog oldestSelectedLog = logAt(
                selectedNotificationId,
                "first-admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z"));
        NotificationAdminActionLog newestSelectedLog = logAt(
                selectedNotificationId,
                "second-admin@example.com",
                Instant.parse("2026-01-01T11:00:00Z"));
        NotificationAdminActionLog otherNotificationLog = logAt(
                otherNotificationId,
                "other-admin@example.com",
                Instant.parse("2026-01-01T12:00:00Z"));
        notificationAdminActionLogRepository.saveAndFlush(oldestSelectedLog);
        notificationAdminActionLogRepository.saveAndFlush(newestSelectedLog);
        notificationAdminActionLogRepository.saveAndFlush(otherNotificationLog);
        entityManager.clear();

        assertThat(notificationAdminActionLogRepository
                .findByNotificationIdOrderByCreatedAtDesc(selectedNotificationId))
                .extracting(NotificationAdminActionLog::getActorEmail)
                .containsExactly("second-admin@example.com", "first-admin@example.com");
    }

    @Test
    void findByNotificationId_shouldReturnPagedSelectedNotificationLogsWithRequestedSortAndPage() {
        UUID selectedNotificationId = UUID.randomUUID();
        UUID otherNotificationId = UUID.randomUUID();
        notificationAdminActionLogRepository.saveAndFlush(logAt(
                selectedNotificationId,
                "first-admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z")));
        notificationAdminActionLogRepository.saveAndFlush(logAt(
                selectedNotificationId,
                "second-admin@example.com",
                Instant.parse("2026-01-01T11:00:00Z")));
        notificationAdminActionLogRepository.saveAndFlush(logAt(
                selectedNotificationId,
                "third-admin@example.com",
                Instant.parse("2026-01-01T12:00:00Z")));
        notificationAdminActionLogRepository.saveAndFlush(logAt(
                otherNotificationId,
                "other-admin@example.com",
                Instant.parse("2026-01-01T13:00:00Z")));
        entityManager.clear();

        Page<NotificationAdminActionLog> page = notificationAdminActionLogRepository.findByNotificationId(
                selectedNotificationId,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent())
                .extracting(NotificationAdminActionLog::getActorEmail)
                .containsExactly("third-admin@example.com", "second-admin@example.com");
    }


    @Test
    void findAllWithAdminFilters_shouldFilterByNotificationId() {
        UUID selectedNotificationId = UUID.randomUUID();
        seedSearchLogs(selectedNotificationId, UUID.randomUUID());

        Page<NotificationAdminActionLog> page = notificationAdminActionLogRepository.findAll(
                NotificationAdminActionLogSpecifications.adminFilters(selectedNotificationId, null, null),
                PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(NotificationAdminActionLog::getNotificationId)
                .containsOnly(selectedNotificationId);
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByActionType() {
        seedSearchLogs(UUID.randomUUID(), UUID.randomUUID());

        Page<NotificationAdminActionLog> page = notificationAdminActionLogRepository.findAll(
                NotificationAdminActionLogSpecifications.adminFilters(null, NotificationAdminActionType.REQUEUE, null),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent())
                .extracting(NotificationAdminActionLog::getActionType)
                .containsOnly(NotificationAdminActionType.REQUEUE);
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByActorEmailContainsIgnoreCase() {
        seedSearchLogs(UUID.randomUUID(), UUID.randomUUID());

        Page<NotificationAdminActionLog> page = notificationAdminActionLogRepository.findAll(
                NotificationAdminActionLogSpecifications.adminFilters(null, null, "  ALPHA  "),
                PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(NotificationAdminActionLog::getActorEmail)
                .containsExactly("Alpha.Admin@example.com");
    }

    @Test
    void findAllWithAdminFilters_shouldCombineNotificationIdAndActorEmail() {
        UUID selectedNotificationId = UUID.randomUUID();
        UUID otherNotificationId = UUID.randomUUID();
        seedSearchLogs(selectedNotificationId, otherNotificationId);

        Page<NotificationAdminActionLog> page = notificationAdminActionLogRepository.findAll(
                NotificationAdminActionLogSpecifications.adminFilters(selectedNotificationId, null, "admin"),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "actorEmail")));

        assertThat(page.getContent())
                .extracting(NotificationAdminActionLog::getActorEmail)
                .containsExactly("Alpha.Admin@example.com", "beta-admin@example.com");
    }

    @Test
    void findAllWithAdminFilters_shouldReturnAllLogsPagedWhenFiltersMissing() {
        seedSearchLogs(UUID.randomUUID(), UUID.randomUUID());

        Page<NotificationAdminActionLog> page = notificationAdminActionLogRepository.findAll(
                NotificationAdminActionLogSpecifications.adminFilters(null, null, "   "),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
    }

    private void seedSearchLogs(UUID selectedNotificationId, UUID otherNotificationId) {
        notificationAdminActionLogRepository.saveAndFlush(logAt(
                selectedNotificationId,
                "Alpha.Admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z")));
        notificationAdminActionLogRepository.saveAndFlush(logAt(
                selectedNotificationId,
                "beta-admin@example.com",
                Instant.parse("2026-01-01T11:00:00Z")));
        notificationAdminActionLogRepository.saveAndFlush(logAt(
                otherNotificationId,
                "gamma-user@example.com",
                Instant.parse("2026-01-01T12:00:00Z")));
        entityManager.clear();
    }

    private NotificationAdminActionLog logAt(UUID notificationId, String actorEmail, Instant createdAt) {
        NotificationAdminActionLog log = NotificationAdminActionLog.requeue(notificationId, actorEmail);
        setCreatedAt(log, createdAt);
        return log;
    }

    private void setCreatedAt(NotificationAdminActionLog log, Instant createdAt) {
        try {
            Field createdAtField = NotificationAdminActionLog.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(log, createdAt);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Failed to set notification admin action log createdAt", ex);
        }
    }
}
