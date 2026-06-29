package com.company.shop.module.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.company.shop.module.notification.NotificationAdminSearchCriteria;
import com.company.shop.module.notification.NotificationDeliveryState;
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
        assertThat(savedNotification.getRequeueCount()).isZero();
        assertThat(savedNotification.getLastRequeuedAt()).isNull();
        assertThat(savedNotification.getLastRequeuedBy()).isNull();
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
        Instant beforeTransition = Instant.now();
        notification.markSent();
        Instant lastAttemptAt = notification.getLastAttemptAt();

        Notification savedNotification = notificationRepository.saveAndFlush(notification);
        entityManager.clear();

        Notification loadedNotification = notificationRepository.findById(savedNotification.getId()).orElseThrow();
        assertThat(loadedNotification.getLastAttemptAt()).isNotNull();
        assertThat(loadedNotification.getSentAt()).isNotNull();
        assertThat(loadedNotification.getLastAttemptAt()).isEqualTo(loadedNotification.getSentAt());
        assertThat(loadedNotification.getLastAttemptAt()).isCloseTo(lastAttemptAt, within(1, ChronoUnit.MILLIS));
        assertThat(loadedNotification.getSentAt()).isCloseTo(lastAttemptAt, within(1, ChronoUnit.MILLIS));
        assertThat(loadedNotification.getLastAttemptAt())
                .isCloseTo(beforeTransition, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void saveAndLoad_shouldPreserveRequeueMetadataAfterRequeueTransition() {
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        notification.markFailed("delivery failed");
        Instant beforeRequeue = Instant.now();
        notification.requeueForDelivery("admin@example.com");

        Notification savedNotification = notificationRepository.saveAndFlush(notification);
        entityManager.clear();

        Notification loadedNotification = notificationRepository.findById(savedNotification.getId()).orElseThrow();
        assertThat(loadedNotification.getRequeueCount()).isEqualTo(1);
        assertThat(loadedNotification.getLastRequeuedAt()).isNotNull();
        assertThat(loadedNotification.getLastRequeuedAt()).isAfterOrEqualTo(beforeRequeue);
        assertThat(loadedNotification.getLastRequeuedAt())
                .isCloseTo(beforeRequeue, within(1, ChronoUnit.SECONDS));
        assertThat(loadedNotification.getLastRequeuedBy()).isEqualTo("admin@example.com");
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
                NotificationSpecifications.adminFilters(criteria(NotificationStatus.PENDING, null, null, null, null)),
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
                NotificationSpecifications.adminFilters(criteria(null, "ORDER_PLACED_EMAIL", null, null, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(orderNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByTypeExactly() {
        Notification matchingNotification = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER",
                "order@example.com",
                "Order notification",
                "Order notification body.",
                UUID.randomUUID()));
        notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, "ORDER", null, null, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterBySourceEventId() {
        UUID matchingSourceEventId = UUID.randomUUID();
        Notification matchingNotification = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "matching@example.com",
                "Order placed",
                "Your order has been placed.",
                matchingSourceEventId));
        notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "different@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));
        notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "null-source@example.com",
                "Order placed",
                "Your order has been placed.",
                null));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .sourceEventId(matchingSourceEventId)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineSourceEventIdWithStatus() {
        UUID sourceEventId = UUID.randomUUID();
        Notification matchingNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "sent@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId);
        matchingNotification.markSent();
        notificationRepository.saveAndFlush(matchingNotification);
        notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "pending@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .status(NotificationStatus.SENT)
                        .sourceEventId(sourceEventId)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineSourceEventIdWithRecipientAndType() {
        UUID sourceEventId = UUID.randomUUID();
        Notification matchingNotification = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "Important.Customer@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId));
        notificationRepository.saveAndFlush(Notification.pending(
                "PASSWORD_RESET_EMAIL",
                "Important.Customer@example.com",
                "Password reset",
                "Reset your password.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .sourceEventId(sourceEventId)
                        .type("ORDER_PLACED_EMAIL")
                        .recipient("customer")
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByCreatedFromInclusively() {
        Instant createdFrom = Instant.parse("2026-06-21T12:00:00Z");
        UUID beforeId = UUID.randomUUID();
        UUID boundaryId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        insertNotification(beforeId, NotificationStatus.PENDING, createdFrom.minusSeconds(1), null);
        insertNotification(boundaryId, NotificationStatus.PENDING, createdFrom, null);
        insertNotification(afterId, NotificationStatus.PENDING, createdFrom.plusSeconds(1), null);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .createdFrom(createdFrom)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(boundaryId, afterId);
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByCreatedToInclusively() {
        Instant createdTo = Instant.parse("2026-06-21T12:00:00Z");
        UUID beforeId = UUID.randomUUID();
        UUID boundaryId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        insertNotification(beforeId, NotificationStatus.PENDING, createdTo.minusSeconds(1), null);
        insertNotification(boundaryId, NotificationStatus.PENDING, createdTo, null);
        insertNotification(afterId, NotificationStatus.PENDING, createdTo.plusSeconds(1), null);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .createdTo(createdTo)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(beforeId, boundaryId);
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByCreatedRangeAndCombineWithStatus() {
        Instant createdFrom = Instant.parse("2026-06-21T00:00:00Z");
        Instant createdTo = Instant.parse("2026-06-21T23:59:59Z");
        UUID matchingId = UUID.randomUUID();
        UUID wrongStatusId = UUID.randomUUID();
        UUID outsideRangeId = UUID.randomUUID();
        insertNotification(matchingId, NotificationStatus.FAILED, createdFrom.plusSeconds(60), null);
        insertNotification(wrongStatusId, NotificationStatus.PENDING, createdFrom.plusSeconds(60), null);
        insertNotification(outsideRangeId, NotificationStatus.FAILED, createdFrom.minusSeconds(60), null);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .status(NotificationStatus.FAILED)
                        .createdFrom(createdFrom)
                        .createdTo(createdTo)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingId);
    }

    @Test
    void findAllWithAdminFilters_shouldCombineCreatedRangeWithSourceEventIdRecipientAndType() {
        UUID sourceEventId = UUID.randomUUID();
        Notification matchingNotification = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "Important.Customer@example.com",
                "Order placed",
                "Your order has been placed.",
                sourceEventId));
        notificationRepository.saveAndFlush(Notification.pending(
                "PASSWORD_RESET_EMAIL",
                "Important.Customer@example.com",
                "Password reset",
                "Reset your password.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .sourceEventId(sourceEventId)
                        .type("ORDER_PLACED_EMAIL")
                        .recipient("customer")
                        .createdFrom(matchingNotification.getCreatedAt())
                        .createdTo(matchingNotification.getCreatedAt().plusSeconds(60))
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingNotification.getId());
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
                NotificationSpecifications.adminFilters(criteria(null, null, "CUSTOMER", null, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(customerNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByLastErrorContainsIgnoreCaseAndTrimInput() {
        Notification matchingNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "timeout@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        matchingNotification.markFailed("SMTP Timeout while sending");
        Notification otherErrorNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "authentication@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        otherErrorNotification.markFailed("SMTP authentication failed");
        Notification nullErrorNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "pending@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        notificationRepository.saveAllAndFlush(List.of(
                matchingNotification,
                otherErrorNotification,
                nullErrorNotification));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, "  timeout  ", null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineLastErrorContainsWithFailedStatus() {
        Notification failedTimeoutNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "failed-timeout@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        failedTimeoutNotification.markFailed("SMTP timeout");
        Notification pendingTimeoutNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "pending-timeout@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        pendingTimeoutNotification.markDeliveryAttemptFailed(
                "SMTP timeout",
                3,
                Instant.now().plus(5, ChronoUnit.MINUTES));
        notificationRepository.saveAllAndFlush(List.of(failedTimeoutNotification, pendingTimeoutNotification));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(NotificationStatus.FAILED, null, null, "timeout", null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(failedTimeoutNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineLastErrorContainsWithRequeuedOnly() {
        Notification requeuedTimeoutNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued-timeout@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedTimeoutNotification.requeueForDelivery("admin@example.com");
        requeuedTimeoutNotification.markFailed("SMTP timeout");
        Notification neverRequeuedTimeoutNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "never-requeued-timeout@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        neverRequeuedTimeoutNotification.markFailed("SMTP timeout");
        notificationRepository.saveAllAndFlush(List.of(
                requeuedTimeoutNotification,
                neverRequeuedTimeoutNotification));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, "timeout", Boolean.TRUE)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(requeuedTimeoutNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterRequeuedOnlyWhenTrue() {
        Notification neverRequeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "never-requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        Notification requeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedNotification.requeueForDelivery("admin@example.com");
        notificationRepository.saveAllAndFlush(List.of(neverRequeuedNotification, requeuedNotification));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, null, Boolean.TRUE)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(requeuedNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldNotFilterRequeuedOnlyWhenFalse() {
        Notification neverRequeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "never-requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        Notification requeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedNotification.requeueForDelivery("admin@example.com");
        notificationRepository.saveAllAndFlush(List.of(neverRequeuedNotification, requeuedNotification));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, null, Boolean.FALSE)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(neverRequeuedNotification.getId(), requeuedNotification.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineRequeuedOnlyWithStatus() {
        Notification requeuedPendingNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued-pending@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedPendingNotification.requeueForDelivery("admin@example.com");
        Notification requeuedFailedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued-failed@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedFailedNotification.requeueForDelivery("admin@example.com");
        requeuedFailedNotification.markFailed("delivery failed");
        Notification neverRequeuedFailedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "failed@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        neverRequeuedFailedNotification.markFailed("delivery failed");
        notificationRepository.saveAllAndFlush(List.of(
                requeuedPendingNotification,
                requeuedFailedNotification,
                neverRequeuedFailedNotification));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(NotificationStatus.FAILED, null, null, null, Boolean.TRUE)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(requeuedFailedNotification.getId());
    }


    @Test
    void findAllWithAdminFilters_shouldFilterByLastRequeuedFrom() {
        Instant boundary = Instant.parse("2026-06-21T12:00:00Z");
        Notification before = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "before-requeued@example.com",
                boundary.minusSeconds(60),
                NotificationStatus.PENDING,
                UUID.randomUUID());
        Notification matching = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "matching-requeued@example.com",
                boundary,
                NotificationStatus.PENDING,
                UUID.randomUUID());
        Notification after = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "after-requeued@example.com",
                boundary.plusSeconds(60),
                NotificationStatus.PENDING,
                UUID.randomUUID());
        Notification neverRequeued = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "never-requeued-range@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        notificationRepository.saveAndFlush(neverRequeued);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .lastRequeuedFrom(boundary)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(matching.getId(), after.getId())
                .doesNotContain(before.getId(), neverRequeued.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByLastRequeuedTo() {
        Instant boundary = Instant.parse("2026-06-21T12:00:00Z");
        Notification before = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "before-requeued-to@example.com",
                boundary.minusSeconds(60),
                NotificationStatus.PENDING,
                UUID.randomUUID());
        Notification matching = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "matching-requeued-to@example.com",
                boundary,
                NotificationStatus.PENDING,
                UUID.randomUUID());
        Notification after = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "after-requeued-to@example.com",
                boundary.plusSeconds(60),
                NotificationStatus.PENDING,
                UUID.randomUUID());

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .lastRequeuedTo(boundary)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(before.getId(), matching.getId())
                .doesNotContain(after.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByLastRequeuedRangeAndComposeWithFilters() {
        Instant from = Instant.parse("2026-06-21T12:00:00Z");
        Instant to = Instant.parse("2026-06-21T13:00:00Z");
        UUID matchingSourceEventId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        Notification matching = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "customer-range@example.com",
                from.plusSeconds(60),
                NotificationStatus.PENDING,
                matchingSourceEventId);
        Notification outsideRange = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "customer-outside-range@example.com",
                to.plusSeconds(60),
                NotificationStatus.PENDING,
                UUID.randomUUID());
        Notification wrongStatus = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "customer-failed-range@example.com",
                from.plusSeconds(120),
                NotificationStatus.FAILED,
                UUID.randomUUID());
        Notification wrongType = notificationWithLastRequeuedAt(
                "PASSWORD_RESET_EMAIL",
                "customer-wrong-type@example.com",
                from.plusSeconds(180),
                NotificationStatus.PENDING,
                UUID.randomUUID());
        Notification wrongSource = notificationWithLastRequeuedAt(
                "ORDER_PLACED_EMAIL",
                "customer-wrong-source@example.com",
                from.plusSeconds(240),
                NotificationStatus.PENDING,
                UUID.randomUUID());

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(NotificationAdminSearchCriteria.builder()
                        .status(NotificationStatus.PENDING)
                        .sourceEventId(matchingSourceEventId)
                        .type("ORDER_PLACED_EMAIL")
                        .recipient("range")
                        .requeuedOnly(Boolean.TRUE)
                        .lastRequeuedFrom(from)
                        .lastRequeuedTo(to)
                        .build()),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matching.getId())
                .doesNotContain(outsideRange.getId(), wrongStatus.getId(), wrongType.getId(), wrongSource.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByAttemptsMin() {
        Notification lowAttempts = notificationWithAttempts("low-attempts@example.com", 1, "temporary timeout");
        Notification matchingAttempts = notificationWithAttempts(
                "matching-attempts@example.com", 3, "temporary timeout");
        notificationRepository.saveAllAndFlush(List.of(lowAttempts, matchingAttempts));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, null, null, 3, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingAttempts.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByAttemptsMax() {
        Notification matchingAttempts = notificationWithAttempts(
                "matching-max-attempts@example.com", 2, "temporary timeout");
        Notification highAttempts = notificationWithAttempts("high-attempts@example.com", 4, "temporary timeout");
        notificationRepository.saveAllAndFlush(List.of(matchingAttempts, highAttempts));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, null, null, null, 2)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matchingAttempts.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByAttemptsRange() {
        Notification belowRange = notificationWithAttempts("below-range@example.com", 1, "temporary timeout");
        Notification insideRange = notificationWithAttempts("inside-range@example.com", 3, "temporary timeout");
        Notification aboveRange = notificationWithAttempts("above-range@example.com", 6, "temporary timeout");
        notificationRepository.saveAllAndFlush(List.of(belowRange, insideRange, aboveRange));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, null, null, 2, 5)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(insideRange.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineAttemptsRangeWithFailedStatus() {
        Notification failedInsideRange = notificationWithAttempts("failed-inside-range@example.com", 3, "SMTP timeout");
        failedInsideRange.markFailed("SMTP timeout");
        Notification pendingInsideRange = notificationWithAttempts(
                "pending-inside-range@example.com", 3, "SMTP timeout");
        notificationRepository.saveAllAndFlush(List.of(failedInsideRange, pendingInsideRange));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(NotificationStatus.FAILED, null, null, null, null, 2, 5)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(failedInsideRange.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineAttemptsRangeWithLastErrorContains() {
        Notification timeoutInsideRange = notificationWithAttempts(
                "timeout-inside-range@example.com", 3, "SMTP timeout");
        Notification authInsideRange = notificationWithAttempts(
                "auth-inside-range@example.com", 3, "SMTP authentication failed");
        notificationRepository.saveAllAndFlush(List.of(timeoutInsideRange, authInsideRange));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteria(null, null, null, "timeout", null, 2, 5)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(timeoutInsideRange.getId());
    }


    @Test
    void findAllWithAdminFilters_shouldFilterByLastAttemptFromInclusively() {
        Instant boundary = Instant.parse("2026-06-21T00:00:00Z");
        Notification before = notificationWithLastAttemptAt(
                "before-last-attempt-from@example.com", boundary.minusSeconds(1), NotificationStatus.FAILED, "timeout");
        Notification atBoundary = notificationWithLastAttemptAt(
                "at-last-attempt-from@example.com", boundary, NotificationStatus.FAILED, "timeout");
        Notification after = notificationWithLastAttemptAt(
                "after-last-attempt-from@example.com", boundary.plusSeconds(1), NotificationStatus.FAILED, "timeout");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, null, null, null, null, null, null, boundary, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(atBoundary.getId(), after.getId())
                .doesNotContain(before.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByLastAttemptToInclusively() {
        Instant boundary = Instant.parse("2026-06-21T23:59:59Z");
        Notification before = notificationWithLastAttemptAt(
                "before-last-attempt-to@example.com", boundary.minusSeconds(1), NotificationStatus.FAILED, "timeout");
        Notification atBoundary = notificationWithLastAttemptAt(
                "at-last-attempt-to@example.com", boundary, NotificationStatus.FAILED, "timeout");
        Notification after = notificationWithLastAttemptAt(
                "after-last-attempt-to@example.com", boundary.plusSeconds(1), NotificationStatus.FAILED, "timeout");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, null, null, null, null, null, null, null, boundary)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(before.getId(), atBoundary.getId())
                .doesNotContain(after.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterByLastAttemptRangeAndExcludeNullLastAttemptAt() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Instant to = Instant.parse("2026-06-21T23:59:59Z");
        Notification before = notificationWithLastAttemptAt(
                "before-last-attempt-range@example.com", from.minusSeconds(1), NotificationStatus.FAILED, "timeout");
        Notification inside = notificationWithLastAttemptAt(
                "inside-last-attempt-range@example.com", from.plusSeconds(60), NotificationStatus.FAILED, "timeout");
        Notification after = notificationWithLastAttemptAt(
                "after-last-attempt-range@example.com", to.plusSeconds(1), NotificationStatus.FAILED, "timeout");
        Notification nullLastAttempt = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "null-last-attempt@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, null, null, null, null, null, null, from, to)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(inside.getId())
                .doesNotContain(before.getId(), after.getId(), nullLastAttempt.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineLastAttemptRangeWithFailedStatus() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Instant to = Instant.parse("2026-06-21T23:59:59Z");
        Notification failedInsideRange = notificationWithLastAttemptAt(
                "failed-last-attempt-range@example.com", from.plusSeconds(60), NotificationStatus.FAILED, "timeout");
        Notification pendingInsideRange = notificationWithLastAttemptAt(
                "pending-last-attempt-range@example.com", from.plusSeconds(60), NotificationStatus.PENDING, "timeout");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(NotificationStatus.FAILED, null, null, null, null, null, null, from, to)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(failedInsideRange.getId())
                .doesNotContain(pendingInsideRange.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineLastAttemptRangeWithLastErrorContains() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Instant to = Instant.parse("2026-06-21T23:59:59Z");
        Notification timeoutInsideRange = notificationWithLastAttemptAt(
                "timeout-last-attempt-range@example.com", from.plusSeconds(60), NotificationStatus.FAILED, "SMTP timeout");
        Notification authInsideRange = notificationWithLastAttemptAt(
                "auth-last-attempt-range@example.com", from.plusSeconds(60), NotificationStatus.FAILED,
                "SMTP authentication failed");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, null, null, "timeout", null, null, null, from, to)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(timeoutInsideRange.getId())
                .doesNotContain(authInsideRange.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldKeepTypeExactMatchWithLastAttemptRange() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Instant to = Instant.parse("2026-06-21T23:59:59Z");
        Notification exactType = notificationWithLastAttemptAtAndType(
                "ORDER", "exact-type-last-attempt-range@example.com", from.plusSeconds(60));
        Notification partialType = notificationWithLastAttemptAtAndType(
                "ORDER_PLACED_EMAIL", "partial-type-last-attempt-range@example.com", from.plusSeconds(60));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, "ORDER", null, null, null, null, null, from, to)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(exactType.getId())
                .doesNotContain(partialType.getId());
    }



    @Test
    void findAllWithAdminFilters_shouldFilterBySentFromInclusively() {
        Instant boundary = Instant.parse("2026-06-21T00:00:00Z");
        Notification before = notificationWithSentAt("before-sent-from@example.com", boundary.minusSeconds(1), "ORDER_PLACED_EMAIL");
        Notification atBoundary = notificationWithSentAt("at-sent-from@example.com", boundary, "ORDER_PLACED_EMAIL");
        Notification after = notificationWithSentAt("after-sent-from@example.com", boundary.plusSeconds(1), "ORDER_PLACED_EMAIL");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteriaWithSentRange(null, null, null, boundary, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(atBoundary.getId(), after.getId())
                .doesNotContain(before.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterBySentToInclusively() {
        Instant boundary = Instant.parse("2026-06-21T23:59:59Z");
        Notification before = notificationWithSentAt("before-sent-to@example.com", boundary.minusSeconds(1), "ORDER_PLACED_EMAIL");
        Notification atBoundary = notificationWithSentAt("at-sent-to@example.com", boundary, "ORDER_PLACED_EMAIL");
        Notification after = notificationWithSentAt("after-sent-to@example.com", boundary.plusSeconds(1), "ORDER_PLACED_EMAIL");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteriaWithSentRange(null, null, null, null, boundary)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(before.getId(), atBoundary.getId())
                .doesNotContain(after.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterBySentRangeAndExcludeNullSentAt() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Instant to = Instant.parse("2026-06-21T23:59:59Z");
        Notification before = notificationWithSentAt("before-sent-range@example.com", from.minusSeconds(1), "ORDER_PLACED_EMAIL");
        Notification inside = notificationWithSentAt("inside-sent-range@example.com", from.plusSeconds(60), "ORDER_PLACED_EMAIL");
        Notification after = notificationWithSentAt("after-sent-range@example.com", to.plusSeconds(1), "ORDER_PLACED_EMAIL");
        Notification nullSentAt = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL",
                "null-sent@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteriaWithSentRange(null, null, null, from, to)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(inside.getId())
                .doesNotContain(before.getId(), after.getId(), nullSentAt.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineSentRangeWithSentStatus() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Notification sentInsideRange = notificationWithSentAt("sent-inside-range@example.com", from.plusSeconds(60), "ORDER_PLACED_EMAIL");
        Notification pendingNullSentAt = notificationRepository.saveAndFlush(Notification.pending(
                "ORDER_PLACED_EMAIL", "pending@example.com", "Order placed", "Your order has been placed.", UUID.randomUUID()));

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteriaWithSentRange(NotificationStatus.SENT, null, null, from, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(sentInsideRange.getId())
                .doesNotContain(pendingNullSentAt.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineSentRangeWithRecipient() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Notification matching = notificationWithSentAt("important.customer@example.com", from.plusSeconds(60), "ORDER_PLACED_EMAIL");
        Notification other = notificationWithSentAt("other@example.com", from.plusSeconds(60), "ORDER_PLACED_EMAIL");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteriaWithSentRange(null, null, "CUSTOMER", from, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matching.getId())
                .doesNotContain(other.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldReturnEmptyForContradictoryFailedStatusAndSentFrom() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Notification sentInsideRange = notificationWithSentAt("sent-contradictory@example.com", from.plusSeconds(60), "ORDER_PLACED_EMAIL");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteriaWithSentRange(NotificationStatus.FAILED, null, null, from, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications).isEmpty();
        assertThat(notifications).extracting(Notification::getId).doesNotContain(sentInsideRange.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldKeepTypeExactMatchWithSentRange() {
        Instant from = Instant.parse("2026-06-21T00:00:00Z");
        Notification exactType = notificationWithSentAt("exact-type-sent-range@example.com", from.plusSeconds(60), "ORDER");
        Notification partialType = notificationWithSentAt("partial-type-sent-range@example.com", from.plusSeconds(60), "ORDER_PLACED_EMAIL");

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(criteriaWithSentRange(null, "ORDER", null, from, null)),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(exactType.getId())
                .doesNotContain(partialType.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterDuePendingDeliveryState() {
        Instant now = Instant.parse("2026-06-21T12:00:00Z");
        Notification nullNextAttempt = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "null-next-attempt@example.com", null, NotificationStatus.PENDING);
        Notification dueAtBoundary = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "due-at-boundary@example.com", now, NotificationStatus.PENDING);
        Notification dueBefore = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "due-before@example.com", now.minusSeconds(1), NotificationStatus.PENDING);
        Notification scheduled = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "scheduled@example.com", now.plusSeconds(1), NotificationStatus.PENDING);
        Notification sentDue = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "sent-due@example.com", now.minusSeconds(1), NotificationStatus.SENT);
        Notification failedDue = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "failed-due@example.com", now.minusSeconds(1), NotificationStatus.FAILED);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, NotificationDeliveryState.DUE_PENDING, null, null, null, null), now),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactlyInAnyOrder(nullNextAttempt.getId(), dueAtBoundary.getId(), dueBefore.getId())
                .doesNotContain(scheduled.getId(), sentDue.getId(), failedDue.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldFilterScheduledPendingDeliveryState() {
        Instant now = Instant.parse("2026-06-21T12:00:00Z");
        Notification scheduled = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "scheduled@example.com", now.plusSeconds(1), NotificationStatus.PENDING);
        Notification nullNextAttempt = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "null-next-attempt@example.com", null, NotificationStatus.PENDING);
        Notification dueAtBoundary = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "due-at-boundary@example.com", now, NotificationStatus.PENDING);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, NotificationDeliveryState.SCHEDULED_PENDING, null, null, null, null), now),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(scheduled.getId())
                .doesNotContain(nullNextAttempt.getId(), dueAtBoundary.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldCombineDeliveryStateWithRecipientAndType() {
        Instant now = Instant.parse("2026-06-21T12:00:00Z");
        Notification matching = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "important.customer@example.com", now.plusSeconds(1), NotificationStatus.PENDING);
        Notification differentRecipient = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "other@example.com", now.plusSeconds(1), NotificationStatus.PENDING);
        Notification differentType = notificationWithNextAttemptAt(
                "PASSWORD_RESET_EMAIL", "important.customer@example.com", now.plusSeconds(1), NotificationStatus.PENDING);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(null, NotificationDeliveryState.SCHEDULED_PENDING, "ORDER_PLACED_EMAIL", "CUSTOMER", null, null), now),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .extracting(Notification::getId)
                .containsExactly(matching.getId())
                .doesNotContain(differentRecipient.getId(), differentType.getId());
    }

    @Test
    void findAllWithAdminFilters_shouldReturnEmptyForContradictoryStatusAndDeliveryState() {
        Instant now = Instant.parse("2026-06-21T12:00:00Z");
        Notification duePending = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "due@example.com", now.minusSeconds(1), NotificationStatus.PENDING);
        Notification sentDue = notificationWithNextAttemptAt(
                "ORDER_PLACED_EMAIL", "sent-due@example.com", now.minusSeconds(1), NotificationStatus.SENT);

        List<Notification> notifications = notificationRepository.findAll(
                NotificationSpecifications.adminFilters(
                        criteria(NotificationStatus.SENT, NotificationDeliveryState.DUE_PENDING, null, null, null, null), now),
                Pageable.unpaged()).getContent();

        assertThat(notifications)
                .isEmpty();
        assertThat(notifications)
                .extracting(Notification::getId)
                .doesNotContain(duePending.getId(), sentDue.getId());
    }

    @Test
    void countByRequeueCountGreaterThan_shouldCountNotificationsWithRequeueCountGreaterThanZero() {
        Notification neverRequeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "never-requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        Notification requeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedNotification.requeueForDelivery("admin@example.com");
        Notification requeuedTwiceNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued-twice@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedTwiceNotification.requeueForDelivery("admin@example.com");
        requeuedTwiceNotification.requeueForDelivery("admin@example.com");
        notificationRepository.saveAllAndFlush(List.of(
                neverRequeuedNotification,
                requeuedNotification,
                requeuedTwiceNotification));

        long requeuedNotificationCount = notificationRepository.countByRequeueCountGreaterThan(0);

        assertThat(requeuedNotificationCount).isEqualTo(2L);
    }

    @Test
    void countByRequeueCountGreaterThan_shouldIgnoreNotificationsWithRequeueCountEqualToZero() {
        Notification neverRequeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "never-requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        notificationRepository.saveAndFlush(neverRequeuedNotification);

        long requeuedNotificationCount = notificationRepository.countByRequeueCountGreaterThan(0);

        assertThat(requeuedNotificationCount).isZero();
    }

    @Test
    void sumRequeueCount_shouldReturnSumAcrossAllNotifications() {
        Notification neverRequeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "never-requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        Notification requeuedNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedNotification.requeueForDelivery("admin@example.com");
        Notification requeuedTwiceNotification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "requeued-twice@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        requeuedTwiceNotification.requeueForDelivery("admin@example.com");
        requeuedTwiceNotification.requeueForDelivery("admin@example.com");
        notificationRepository.saveAllAndFlush(List.of(
                neverRequeuedNotification,
                requeuedNotification,
                requeuedTwiceNotification));

        long totalRequeueCount = notificationRepository.sumRequeueCount();

        assertThat(totalRequeueCount).isEqualTo(3L);
    }

    @Test
    void sumRequeueCount_shouldReturnZeroWhenThereAreNoNotifications() {
        long totalRequeueCount = notificationRepository.sumRequeueCount();

        assertThat(totalRequeueCount).isZero();
    }

    @Test
    void insert_shouldUseDatabaseDefaultsForStatusCreatedAtAttemptsLastAttemptAtNextAttemptAtAndRequeueMetadata() {
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
                        SELECT status, created_at, sent_at, attempts, requeue_count, last_requeued_at,
                               last_requeued_by, last_error, last_attempt_at, next_attempt_at
                        FROM notifications
                        WHERE id = ?
                        """,
                notificationId);

        assertThat(defaults)
                .containsEntry("status", NotificationStatus.PENDING.name())
                .containsEntry("sent_at", null)
                .containsEntry("attempts", 0)
                .containsEntry("requeue_count", 0)
                .containsEntry("last_requeued_at", null)
                .containsEntry("last_requeued_by", null)
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


    private NotificationAdminSearchCriteria criteria(
            NotificationStatus status,
            NotificationDeliveryState deliveryState,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly) {
        return NotificationAdminSearchCriteria.builder()
                .status(status)
                .deliveryState(deliveryState)
                .type(type)
                .recipient(recipient)
                .lastErrorContains(lastErrorContains)
                .requeuedOnly(requeuedOnly)
                .build();
    }

    private NotificationAdminSearchCriteria criteria(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly) {
        return criteria(status, type, recipient, lastErrorContains, requeuedOnly, null, null);
    }

    private NotificationAdminSearchCriteria criteria(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax) {
        return criteria(status, type, recipient, lastErrorContains, requeuedOnly, attemptsMin, attemptsMax, null, null);
    }

    private NotificationAdminSearchCriteria criteria(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax,
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        return NotificationAdminSearchCriteria.builder()
                .status(status)
                .type(type)
                .recipient(recipient)
                .lastErrorContains(lastErrorContains)
                .requeuedOnly(requeuedOnly)
                .attemptsMin(attemptsMin)
                .attemptsMax(attemptsMax)
                .lastAttemptFrom(lastAttemptFrom)
                .lastAttemptTo(lastAttemptTo)
                .build();
    }



    private NotificationAdminSearchCriteria criteriaWithSentRange(
            NotificationStatus status,
            String type,
            String recipient,
            Instant sentFrom,
            Instant sentTo) {
        return NotificationAdminSearchCriteria.builder()
                .status(status)
                .type(type)
                .recipient(recipient)
                .sentFrom(sentFrom)
                .sentTo(sentTo)
                .build();
    }

    private Notification notificationWithNextAttemptAt(
            String type,
            String recipient,
            Instant nextAttemptAt,
            NotificationStatus status) {
        Notification notification = notificationRepository.saveAndFlush(Notification.pending(
                type,
                recipient,
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID()));
        jdbcTemplate.update(
                "UPDATE notifications SET status = ?, sent_at = ?, last_error = ?, next_attempt_at = ? WHERE id = ?",
                status.name(),
                status == NotificationStatus.SENT ? java.sql.Timestamp.from(Instant.parse("2026-06-21T12:01:00Z")) : null,
                status == NotificationStatus.FAILED ? "delivery failed" : null,
                nextAttemptAt == null ? null : java.sql.Timestamp.from(nextAttemptAt),
                notification.getId());
        entityManager.clear();
        return notification;
    }



    private Notification notificationWithLastRequeuedAt(
            String type,
            String recipient,
            Instant lastRequeuedAt,
            NotificationStatus status,
            UUID sourceEventId) {
        Notification notification = Notification.pending(
                type,
                recipient,
                "Order placed",
                "Your order has been placed.",
                sourceEventId);
        notification.requeueForDelivery("admin@example.com");
        Notification saved = notificationRepository.saveAndFlush(notification);
        jdbcTemplate.update(
                "UPDATE notifications SET status = ?, last_requeued_at = ?, last_error = ? WHERE id = ?",
                status.name(),
                java.sql.Timestamp.from(lastRequeuedAt),
                status == NotificationStatus.FAILED ? "delivery failed" : null,
                saved.getId());
        entityManager.clear();
        return saved;
    }

    private Notification notificationWithSentAt(String recipient, Instant sentAt, String type) {
        Notification notification = Notification.pending(
                type,
                recipient,
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        Notification saved = notificationRepository.saveAndFlush(notification);
        jdbcTemplate.update(
                "UPDATE notifications SET status = 'SENT', sent_at = ?, last_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(sentAt),
                java.sql.Timestamp.from(sentAt),
                saved.getId());
        entityManager.clear();
        return saved;
    }

    private Notification notificationWithLastAttemptAt(
            String recipient,
            Instant lastAttemptAt,
            NotificationStatus status,
            String errorMessage) {
        Notification notification = notificationWithLastAttemptAtAndType(
                "ORDER_PLACED_EMAIL", recipient, lastAttemptAt);
        if (status == NotificationStatus.PENDING) {
            jdbcTemplate.update(
                    "UPDATE notifications SET status = 'PENDING', last_error = ? WHERE id = ?",
                    errorMessage,
                    notification.getId());
        } else {
            jdbcTemplate.update(
                    "UPDATE notifications SET status = ?, last_error = ? WHERE id = ?",
                    status.name(),
                    errorMessage,
                    notification.getId());
        }
        entityManager.clear();
        return notification;
    }

    private Notification notificationWithLastAttemptAtAndType(String type, String recipient, Instant lastAttemptAt) {
        Notification notification = Notification.pending(
                type,
                recipient,
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        notification.markFailed("SMTP timeout");
        Notification saved = notificationRepository.saveAndFlush(notification);
        jdbcTemplate.update(
                "UPDATE notifications SET last_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(lastAttemptAt),
                saved.getId());
        entityManager.clear();
        return saved;
    }

    private Notification notificationWithAttempts(String recipient, int attempts, String errorMessage) {
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                recipient,
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());
        for (int i = 0; i < attempts; i++) {
            notification.markDeliveryAttemptFailed(
                    errorMessage, attempts + 1, Instant.now().plus(5, ChronoUnit.MINUTES));
        }
        return notification;
    }

}
