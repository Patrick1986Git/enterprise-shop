package com.company.shop.module.order.outbox;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OutboxEventRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanOutboxEvents() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void save_shouldPersistPendingOutboxEvent() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(
                "Order",
                aggregateId,
                "TestEvent",
                "{\"orderId\":\"" + aggregateId + "\"}");

        OutboxEvent savedEvent = outboxEventRepository.saveAndFlush(event);

        assertThat(savedEvent.getId()).isNotNull();
        assertThat(savedEvent.getAggregateType()).isEqualTo("Order");
        assertThat(savedEvent.getAggregateId()).isEqualTo(aggregateId);
        assertThat(savedEvent.getEventType()).isEqualTo("TestEvent");
        assertThat(savedEvent.getPayload()).contains(aggregateId.toString());
        assertThat(savedEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(savedEvent.getCreatedAt()).isNotNull();
        assertThat(savedEvent.getProcessedAt()).isNull();
        assertThat(savedEvent.getLastAttemptAt()).isNull();
        assertThat(savedEvent.getAttempts()).isZero();
        assertThat(savedEvent.getLastError()).isNull();
        assertThat(savedEvent.getRequeueCount()).isZero();
        assertThat(savedEvent.getLastRequeuedAt()).isNull();
        assertThat(savedEvent.getLastRequeuedBy()).isNull();
    }

    @Test
    void insert_shouldUseDatabaseDefaultsForStatusAndAttempts() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                    """);
            statement.setObject(1, eventId);
            statement.setString(2, "Order");
            statement.setObject(3, aggregateId);
            statement.setString(4, "TestEvent");
            statement.setString(5, "{\"orderId\":\"" + aggregateId + "\"}");
            return statement;
        });

        Map<String, Object> defaults = jdbcTemplate.queryForMap(
                "SELECT status, attempts, requeue_count, last_attempt_at, last_requeued_at, last_requeued_by FROM outbox_events WHERE id = ?",
                eventId);

        assertThat(defaults)
                .containsEntry("status", OutboxEventStatus.PENDING.name())
                .containsEntry("attempts", 0)
                .containsEntry("requeue_count", 0)
                .containsEntry("last_attempt_at", null)
                .containsEntry("last_requeued_at", null)
                .containsEntry("last_requeued_by", null);
    }

    @Test
    void saveAndLoad_shouldPreserveRequeueMetadataAfterRequeueTransition() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        event.markFailed("boom");
        event.requeueForProcessing("admin@example.com");
        Instant requeuedAt = event.getLastRequeuedAt();

        UUID savedId = outboxEventRepository.saveAndFlush(event).getId();
        entityManager.clear();

        OutboxEvent loadedEvent = outboxEventRepository.findById(savedId).orElseThrow();

        assertThat(loadedEvent.getRequeueCount()).isEqualTo(1);
        assertThat(loadedEvent.getLastRequeuedAt()).isCloseTo(requeuedAt, within(1, ChronoUnit.MILLIS));
        assertThat(loadedEvent.getLastRequeuedBy()).isEqualTo("admin@example.com");
    }


    @Test
    void saveAndLoad_shouldPreserveLastAttemptAtAfterFailedTransition() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        event.markFailed("boom");
        Instant lastAttemptAt = event.getLastAttemptAt();

        UUID savedId = outboxEventRepository.saveAndFlush(event).getId();
        entityManager.clear();

        OutboxEvent loadedEvent = outboxEventRepository.findById(savedId).orElseThrow();

        assertThat(loadedEvent.getLastAttemptAt()).isCloseTo(lastAttemptAt, within(1, ChronoUnit.MILLIS));
        assertThat(loadedEvent.getProcessedAt()).isNull();
    }

    @Test
    void saveAndLoad_shouldPreserveLastAttemptAtAfterProcessedTransition() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        event.markProcessed();
        Instant lastAttemptAt = event.getLastAttemptAt();

        UUID savedId = outboxEventRepository.saveAndFlush(event).getId();
        entityManager.clear();

        OutboxEvent loadedEvent = outboxEventRepository.findById(savedId).orElseThrow();

        assertThat(loadedEvent.getLastAttemptAt()).isCloseTo(lastAttemptAt, within(1, ChronoUnit.MILLIS));
        assertThat(loadedEvent.getProcessedAt()).isCloseTo(lastAttemptAt, within(1, ChronoUnit.MILLIS));
    }

    @Test
    void findPendingBatchForUpdate_shouldReturnPendingEventsOrderedByCreatedAt() {
        UUID earliestPendingId = UUID.randomUUID();
        UUID processedId = UUID.randomUUID();
        UUID latestPendingId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        UUID middlePendingId = UUID.randomUUID();

        insertOutboxEvent(earliestPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"));
        insertOutboxEvent(processedId, OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:01:00Z"));
        insertOutboxEvent(latestPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:04:00Z"));
        insertOutboxEvent(failedId, OutboxEventStatus.FAILED, Instant.parse("2026-01-01T10:02:00Z"));
        insertOutboxEvent(middlePendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:03:00Z"));

        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingBatchForUpdate(10);

        assertThat(pendingEvents)
                .extracting(OutboxEvent::getId)
                .containsExactly(earliestPendingId, middlePendingId, latestPendingId)
                .doesNotContain(processedId, failedId);
        assertThat(pendingEvents)
                .extracting(OutboxEvent::getStatus)
                .containsOnly(OutboxEventStatus.PENDING);
    }

    @Test
    void findPendingBatchForUpdate_shouldRespectBatchSize() {
        UUID firstPendingId = UUID.randomUUID();
        UUID secondPendingId = UUID.randomUUID();

        insertOutboxEvent(firstPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:00:00Z"));
        insertOutboxEvent(secondPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:01:00Z"));

        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingBatchForUpdate(1);

        assertThat(pendingEvents)
                .extracting(OutboxEvent::getId)
                .containsExactly(firstPendingId);
    }

    @Test
    void summaryQueries_shouldReturnCountsAndOperationalTimestamps() {
        Instant oldestPendingCreatedAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant newestFailedCreatedAt = Instant.parse("2026-01-01T10:04:00Z");

        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PENDING, oldestPendingCreatedAt);
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:03:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:01:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:02:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.FAILED, Instant.parse("2026-01-01T09:59:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.FAILED, newestFailedCreatedAt);

        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).isEqualTo(2L);
        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED)).isEqualTo(2L);
        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.FAILED)).isEqualTo(2L);
        assertThat(outboxEventRepository.count()).isEqualTo(6L);
        assertThat(outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING))
                .hasValueSatisfying(actual -> assertThat(actual).isEqualTo(oldestPendingCreatedAt));
        assertThat(outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED))
                .hasValueSatisfying(actual -> assertThat(actual).isEqualTo(newestFailedCreatedAt));
    }


    @Test
    void operationalProblemIndicatorCounts_shouldCountMatchingEventsOnly() {
        Instant threshold = Instant.parse("2026-01-01T10:15:00Z");

        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:15:00Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:15:01Z"));
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:00:00Z"));
        insertOutboxEventWithAttemptMetadata(
                UUID.randomUUID(), OutboxEventStatus.FAILED, Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:15:00Z"), 3);
        insertOutboxEventWithAttemptMetadata(
                UUID.randomUUID(), OutboxEventStatus.FAILED, Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:15:01Z"), 2);
        insertOutboxEventWithAttemptMetadata(
                UUID.randomUUID(), OutboxEventStatus.FAILED, Instant.parse("2026-01-01T10:00:00Z"), null, 4);
        insertOutboxEventWithAttemptMetadata(
                UUID.randomUUID(), OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T10:15:00Z"), 3);

        assertThat(outboxEventRepository.countByStatusAndCreatedAtLessThanEqual(OutboxEventStatus.PENDING, threshold))
                .isEqualTo(1L);
        assertThat(outboxEventRepository.countByStatusAndLastAttemptAtLessThanEqual(OutboxEventStatus.FAILED, threshold))
                .isEqualTo(1L);
        assertThat(outboxEventRepository.countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus.FAILED, 3))
                .isEqualTo(2L);
    }

    @Test
    void findNewestAttemptAt_shouldReturnNewestLastAttemptAtAcrossAllEvents() {
        OutboxEvent processedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        processedEvent.markProcessed();

        OutboxEvent failedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        failedEvent.markFailed("boom");
        Instant newerAttemptAt = failedEvent.getLastAttemptAt();

        outboxEventRepository.saveAllAndFlush(List.of(processedEvent, failedEvent));

        assertThat(outboxEventRepository.findNewestAttemptAt())
                .hasValueSatisfying(actual -> assertThat(actual).isCloseTo(newerAttemptAt, within(1, ChronoUnit.MILLIS)));
    }

    @Test
    void findNewestAttemptAt_shouldReturnEmptyWhenNoEventsHaveLastAttemptAt() {
        outboxEventRepository.saveAndFlush(OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}"));

        assertThat(outboxEventRepository.findNewestAttemptAt()).isEmpty();
    }

    @Test
    void findNewestAttemptAtByStatus_shouldReturnNewestProcessedAttemptTimestamp() {
        OutboxEvent olderProcessedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        olderProcessedEvent.markProcessed();
        OutboxEvent newestProcessedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        newestProcessedEvent.markProcessed();
        Instant newestProcessedAttemptAt = newestProcessedEvent.getLastAttemptAt();

        outboxEventRepository.saveAllAndFlush(List.of(olderProcessedEvent, newestProcessedEvent));

        assertThat(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.PROCESSED))
                .hasValueSatisfying(actual -> assertThat(actual).isCloseTo(newestProcessedAttemptAt, within(1, ChronoUnit.MILLIS)));
    }

    @Test
    void findNewestAttemptAtByStatus_shouldReturnNewestFailedAttemptTimestamp() {
        OutboxEvent olderFailedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        olderFailedEvent.markFailed("older");
        OutboxEvent newestFailedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        newestFailedEvent.markFailed("newer");
        Instant newestFailedAttemptAt = newestFailedEvent.getLastAttemptAt();

        outboxEventRepository.saveAllAndFlush(List.of(olderFailedEvent, newestFailedEvent));

        assertThat(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.FAILED))
                .hasValueSatisfying(actual -> assertThat(actual).isCloseTo(newestFailedAttemptAt, within(1, ChronoUnit.MILLIS)));
    }

    @Test
    void findNewestAttemptAtByStatus_shouldIgnoreEventsOfOtherStatuses() {
        OutboxEvent processedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        processedEvent.markProcessed();
        Instant processedAttemptAt = processedEvent.getLastAttemptAt();

        OutboxEvent failedEvent = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        failedEvent.markFailed("boom");

        outboxEventRepository.saveAllAndFlush(List.of(processedEvent, failedEvent));

        assertThat(outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.PROCESSED))
                .hasValueSatisfying(actual -> assertThat(actual).isCloseTo(processedAttemptAt, within(1, ChronoUnit.MILLIS)));
    }

    @Test
    void countByRequeueCountGreaterThan_shouldCountOnlyRequeuedEvents() {
        OutboxEvent requeuedOnce = requeuedEvent(1);
        OutboxEvent requeuedTwice = requeuedEvent(2);
        OutboxEvent neverRequeued = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":3}");
        outboxEventRepository.saveAllAndFlush(List.of(requeuedOnce, requeuedTwice, neverRequeued));

        assertThat(outboxEventRepository.countByRequeueCountGreaterThan(0)).isEqualTo(2L);
    }

    @Test
    void sumRequeueCount_shouldReturnSumAcrossAllEvents() {
        OutboxEvent requeuedOnce = requeuedEvent(1);
        OutboxEvent requeuedTwice = requeuedEvent(2);
        OutboxEvent neverRequeued = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":3}");
        outboxEventRepository.saveAllAndFlush(List.of(requeuedOnce, requeuedTwice, neverRequeued));

        assertThat(outboxEventRepository.sumRequeueCount()).isEqualTo(3L);
    }

    @Test
    void sumRequeueCount_shouldReturnZeroWhenNoEventsExist() {
        assertThat(outboxEventRepository.sumRequeueCount()).isZero();
    }


    @Test
    void findAll_shouldFilterByStatus() {
        UUID pendingId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        insertOutboxEvent(pendingId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(failedId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderFailed");

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, null, null, null, null, null, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(failedId);
    }

    @Test
    void findAll_shouldFilterByAggregateTypeContainsIgnoreCase() {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        insertOutboxEvent(orderId, OutboxEventStatus.PENDING, "SalesOrder", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(cartId, OutboxEventStatus.PENDING, "Cart", UUID.randomUUID(), "CartCheckedOut");

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, " order ", null, null, null, null, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(orderId);
    }

    @Test
    void findAll_shouldFilterByEventTypeContainsIgnoreCase() {
        UUID placedId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        insertOutboxEvent(placedId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(failedId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "PaymentFailed");

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, " placed ", null, null, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(placedId);
    }

    @Test
    void findAll_shouldFilterByLastErrorContainsIgnoreCaseAndTrimInput() {
        OutboxEvent timeout = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":1}");
        timeout.markFailed("SMTP TIMEOUT while sending email");
        OutboxEvent serialization = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":2}");
        serialization.markFailed("Serialization failed");
        OutboxEvent withoutError = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":3}");
        outboxEventRepository.saveAllAndFlush(List.of(timeout, serialization, withoutError));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, " timeout ", null, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(OutboxEvent::getId)
                .containsExactly(timeout.getId())
                .doesNotContain(serialization.getId(), withoutError.getId());
    }

    @Test
    void findAll_shouldCombineLastErrorContainsWithStatusAndAttemptsFilters() {
        OutboxEvent matching = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":1}");
        matching.markFailed("Connection timeout");
        matching.markFailed("Connection timeout");
        OutboxEvent wrongStatus = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":2}");
        wrongStatus.markFailed("Connection timeout");
        wrongStatus.markProcessed();
        OutboxEvent wrongAttempts = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":3}");
        wrongAttempts.markFailed("Connection timeout");
        OutboxEvent wrongError = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{\"id\":4}");
        wrongError.markFailed("Serialization failed");
        wrongError.markFailed("Serialization failed");
        outboxEventRepository.saveAllAndFlush(List.of(matching, wrongStatus, wrongAttempts, wrongError));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, null, null, null, "TIMEOUT", null, null, null, null, 2, 3, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matching.getId());
    }

    @Test
    void findAll_shouldFilterByAggregateId() {
        UUID matchingAggregateId = UUID.randomUUID();
        UUID matchingEventId = UUID.randomUUID();
        UUID otherEventId = UUID.randomUUID();
        insertOutboxEvent(matchingEventId, OutboxEventStatus.PENDING, "Order", matchingAggregateId, "OrderPlaced");
        insertOutboxEvent(otherEventId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced");

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, matchingAggregateId, null, null, null, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matchingEventId);
    }

    @Test
    void findAll_shouldCombineStatusAndAggregateTypeFilters() {
        UUID matchingId = UUID.randomUUID();
        UUID processedOrderId = UUID.randomUUID();
        UUID pendingCartId = UUID.randomUUID();
        insertOutboxEvent(matchingId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(processedOrderId, OutboxEventStatus.PROCESSED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(pendingCartId, OutboxEventStatus.PENDING, "Cart", UUID.randomUUID(), "CartCheckedOut");

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.PENDING, "ord", null, null, null, null, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matchingId);
    }

    @Test
    void findAll_shouldReturnAllEventsPagedWhenFiltersAreMissingOrBlank() {
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.FAILED, "Cart", UUID.randomUUID(), "CartCheckedOut");
        insertOutboxEvent(UUID.randomUUID(), OutboxEventStatus.PROCESSED, "Payment", UUID.randomUUID(), "PaymentCaptured");

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, " ", null, " ", null, null, null, null, null, null, null, null)),
                PageRequest.of(0, 2));

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }


    @Test
    void findAll_shouldFilterByCreatedFromInclusive() {
        UUID beforeId = UUID.randomUUID();
        UUID atLowerBoundId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        insertOutboxEvent(beforeId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-05-31T23:59:59Z"));
        insertOutboxEvent(atLowerBoundId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced", lowerBound);
        insertOutboxEvent(afterId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-06-01T00:00:01Z"));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, lowerBound, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(atLowerBoundId, afterId)
                .doesNotContain(beforeId);
    }

    @Test
    void findAll_shouldFilterByCreatedToInclusive() {
        UUID beforeId = UUID.randomUUID();
        UUID atUpperBoundId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        insertOutboxEvent(beforeId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-06-30T23:59:58Z"));
        insertOutboxEvent(atUpperBoundId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced", upperBound);
        insertOutboxEvent(afterId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-07-01T00:00:00Z"));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, upperBound, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(beforeId, atUpperBoundId)
                .doesNotContain(afterId);
    }

    @Test
    void findAll_shouldFilterByCreatedRangeInclusive() {
        UUID beforeId = UUID.randomUUID();
        UUID insideId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        insertOutboxEvent(beforeId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-05-31T23:59:59Z"));
        insertOutboxEvent(insideId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-06-15T12:00:00Z"));
        insertOutboxEvent(afterId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-07-01T00:00:00Z"));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, lowerBound, upperBound, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(insideId);
    }

    @Test
    void findAll_shouldCombineDateRangeWithStatus() {
        UUID matchingId = UUID.randomUUID();
        UUID wrongStatusId = UUID.randomUUID();
        UUID outsideRangeId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        insertOutboxEvent(matchingId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-06-15T12:00:00Z"));
        insertOutboxEvent(wrongStatusId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-06-15T12:00:00Z"));
        insertOutboxEvent(outsideRangeId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-07-01T00:00:00Z"));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, null, null, null, null, lowerBound, upperBound, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matchingId);
    }

    @Test
    void findAll_shouldCombineDateRangeWithAggregateTypeAndEventType() {
        UUID matchingId = UUID.randomUUID();
        UUID wrongAggregateTypeId = UUID.randomUUID();
        UUID wrongEventTypeId = UUID.randomUUID();
        UUID outsideRangeId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        insertOutboxEvent(matchingId, OutboxEventStatus.PENDING, "SalesOrder", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-06-15T12:00:00Z"));
        insertOutboxEvent(wrongAggregateTypeId, OutboxEventStatus.PENDING, "Cart", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-06-15T12:00:00Z"));
        insertOutboxEvent(wrongEventTypeId, OutboxEventStatus.PENDING, "SalesOrder", UUID.randomUUID(), "PaymentFailed",
                Instant.parse("2026-06-15T12:00:00Z"));
        insertOutboxEvent(outsideRangeId, OutboxEventStatus.PENDING, "SalesOrder", UUID.randomUUID(), "OrderPlaced",
                Instant.parse("2026-07-01T00:00:00Z"));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, " order ", null, " placed ", null, lowerBound, upperBound, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matchingId);
    }


    @Test
    void findAll_shouldFilterByRequeuedOnlyTrue() {
        OutboxEvent requeued = requeuedEvent(1);
        OutboxEvent neverRequeued = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        outboxEventRepository.saveAllAndFlush(List.of(requeued, neverRequeued));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE)),
                PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(OutboxEvent::getId)
                .containsExactly(requeued.getId())
                .doesNotContain(neverRequeued.getId());
    }

    @Test
    void findAll_shouldNotFilterByRequeuedOnlyFalse() {
        OutboxEvent requeued = requeuedEvent(1);
        OutboxEvent neverRequeued = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        outboxEventRepository.saveAllAndFlush(List.of(requeued, neverRequeued));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, Boolean.FALSE)),
                PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(requeued.getId(), neverRequeued.getId());
    }

    @Test
    void findAll_shouldNotFilterByRequeuedOnlyMissing() {
        OutboxEvent requeued = requeuedEvent(1);
        OutboxEvent neverRequeued = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        outboxEventRepository.saveAllAndFlush(List.of(requeued, neverRequeued));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(requeued.getId(), neverRequeued.getId());
    }

    @Test
    void findAll_shouldCombineRequeuedOnlyWithStatus() {
        OutboxEvent matching = requeuedEvent(1);
        OutboxEvent wrongStatus = requeuedEvent(1);
        wrongStatus.markProcessed();
        OutboxEvent neverRequeued = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":3}");
        outboxEventRepository.saveAllAndFlush(List.of(matching, wrongStatus, neverRequeued));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.PENDING, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matching.getId());
    }

    @Test
    void findAll_shouldCombineRequeuedOnlyWithCreatedRange() {
        OutboxEvent before = requeuedEvent(1);
        OutboxEvent inside = requeuedEvent(1);
        OutboxEvent neverRequeuedInside = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":3}");
        outboxEventRepository.saveAllAndFlush(List.of(before, inside, neverRequeuedInside));
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        updateCreatedAt(before.getId(), Instant.parse("2026-05-31T23:59:59Z"));
        updateCreatedAt(inside.getId(), Instant.parse("2026-06-15T12:00:00Z"));
        updateCreatedAt(neverRequeuedInside.getId(), Instant.parse("2026-06-15T12:00:00Z"));
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, lowerBound, upperBound, null, null, null, null, Boolean.TRUE)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(inside.getId());
    }

    @Test
    void findAll_shouldFilterByLastAttemptFromInclusive() {
        UUID beforeId = UUID.randomUUID();
        UUID atLowerBoundId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        insertOutboxEvent(beforeId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(atLowerBoundId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(afterId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        updateLastAttemptAt(beforeId, Instant.parse("2026-05-31T23:59:59Z"));
        updateLastAttemptAt(atLowerBoundId, lowerBound);
        updateLastAttemptAt(afterId, Instant.parse("2026-06-01T00:00:01Z"));
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lowerBound, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(atLowerBoundId, afterId)
                .doesNotContain(beforeId);
    }

    @Test
    void findAll_shouldFilterByLastAttemptToInclusive() {
        UUID beforeId = UUID.randomUUID();
        UUID atUpperBoundId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        insertOutboxEvent(beforeId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(atUpperBoundId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(afterId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        updateLastAttemptAt(beforeId, Instant.parse("2026-06-30T23:59:58Z"));
        updateLastAttemptAt(atUpperBoundId, upperBound);
        updateLastAttemptAt(afterId, Instant.parse("2026-07-01T00:00:00Z"));
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, upperBound, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(beforeId, atUpperBoundId)
                .doesNotContain(afterId);
    }

    @Test
    void findAll_shouldFilterByLastAttemptRangeInclusive() {
        UUID beforeId = UUID.randomUUID();
        UUID insideId = UUID.randomUUID();
        UUID afterId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        insertOutboxEvent(beforeId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(insideId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(afterId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        updateLastAttemptAt(beforeId, Instant.parse("2026-05-31T23:59:59Z"));
        updateLastAttemptAt(insideId, Instant.parse("2026-06-15T12:00:00Z"));
        updateLastAttemptAt(afterId, Instant.parse("2026-07-01T00:00:00Z"));
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lowerBound, upperBound, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(insideId);
    }

    @Test
    void findAll_shouldExcludeNullLastAttemptAtWhenLastAttemptRangeIsApplied() {
        UUID pendingId = UUID.randomUUID();
        UUID attemptedId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        insertOutboxEvent(pendingId, OutboxEventStatus.PENDING, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(attemptedId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        updateLastAttemptAt(attemptedId, lowerBound);
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lowerBound, null, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactly(attemptedId)
                .doesNotContain(pendingId);
    }

    @Test
    void findAll_shouldCombineLastAttemptRangeWithStatus() {
        UUID matchingId = UUID.randomUUID();
        UUID wrongStatusId = UUID.randomUUID();
        UUID outsideRangeId = UUID.randomUUID();
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        insertOutboxEvent(matchingId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(wrongStatusId, OutboxEventStatus.PROCESSED, "Order", UUID.randomUUID(), "OrderPlaced");
        insertOutboxEvent(outsideRangeId, OutboxEventStatus.FAILED, "Order", UUID.randomUUID(), "OrderPlaced");
        updateLastAttemptAt(matchingId, Instant.parse("2026-06-15T12:00:00Z"));
        updateLastAttemptAt(wrongStatusId, Instant.parse("2026-06-15T12:00:00Z"));
        updateLastAttemptAt(outsideRangeId, Instant.parse("2026-07-01T00:00:00Z"));
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, null, null, null, null, null, null, lowerBound, upperBound, null, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matchingId);
    }

    @Test
    void findAll_shouldCombineLastAttemptRangeWithRequeuedOnly() {
        OutboxEvent matching = requeuedEvent(1);
        OutboxEvent neverRequeued = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":2}");
        neverRequeued.markFailed("boom");
        outboxEventRepository.saveAllAndFlush(List.of(matching, neverRequeued));
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        updateLastAttemptAt(matching.getId(), Instant.parse("2026-06-15T12:00:00Z"));
        updateLastAttemptAt(neverRequeued.getId(), Instant.parse("2026-06-15T12:00:00Z"));
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, lowerBound, upperBound, null, null, Boolean.TRUE)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matching.getId());
    }

    @Test
    void findAll_shouldFilterByAttemptsMinInclusive() {
        OutboxEvent below = attemptedEvent(1);
        OutboxEvent atLowerBound = attemptedEvent(2);
        OutboxEvent above = attemptedEvent(3);
        outboxEventRepository.saveAllAndFlush(List.of(below, atLowerBound, above));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 2, null, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(atLowerBound.getId(), above.getId())
                .doesNotContain(below.getId());
    }

    @Test
    void findAll_shouldFilterByAttemptsMaxInclusive() {
        OutboxEvent below = attemptedEvent(1);
        OutboxEvent atUpperBound = attemptedEvent(2);
        OutboxEvent above = attemptedEvent(3);
        outboxEventRepository.saveAllAndFlush(List.of(below, atUpperBound, above));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, null, 2, null)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(below.getId(), atUpperBound.getId())
                .doesNotContain(above.getId());
    }

    @Test
    void findAll_shouldFilterByAttemptsRangeInclusiveAndExactRange() {
        OutboxEvent below = attemptedEvent(1);
        OutboxEvent exact = attemptedEvent(2);
        OutboxEvent above = attemptedEvent(3);
        outboxEventRepository.saveAllAndFlush(List.of(below, exact, above));

        Page<OutboxEvent> rangeResult = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 2, 3, null)),
                PageRequest.of(0, 10));
        Page<OutboxEvent> exactResult = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    null, null, null, null, null, null, null, null, null, 2, 2, null)),
                PageRequest.of(0, 10));

        assertThat(rangeResult.getContent()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(exact.getId(), above.getId())
                .doesNotContain(below.getId());
        assertThat(exactResult.getContent()).extracting(OutboxEvent::getId).containsExactly(exact.getId());
    }

    @Test
    void findAll_shouldCombineAttemptsRangeWithStatusLastAttemptAndRequeuedOnly() {
        OutboxEvent matching = attemptedEvent(3);
        matching.requeueForProcessing("admin@example.com");
        matching.markFailed("boom");
        OutboxEvent wrongStatus = attemptedEvent(3);
        wrongStatus.markProcessed();
        OutboxEvent wrongAttempts = attemptedEvent(0);
        wrongAttempts.requeueForProcessing("admin@example.com");
        wrongAttempts.markFailed("boom");
        OutboxEvent wrongRequeued = attemptedEvent(3);
        outboxEventRepository.saveAllAndFlush(List.of(matching, wrongStatus, wrongAttempts, wrongRequeued));
        Instant lowerBound = Instant.parse("2026-06-01T00:00:00Z");
        Instant upperBound = Instant.parse("2026-06-30T23:59:59Z");
        updateLastAttemptAt(matching.getId(), Instant.parse("2026-06-15T12:00:00Z"));
        updateLastAttemptAt(wrongStatus.getId(), Instant.parse("2026-06-15T12:00:00Z"));
        updateLastAttemptAt(wrongAttempts.getId(), Instant.parse("2026-06-15T12:00:00Z"));
        updateLastAttemptAt(wrongRequeued.getId(), Instant.parse("2026-07-01T00:00:00Z"));
        entityManager.clear();

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                    OutboxEventStatus.FAILED, null, null, null, null, null, null, lowerBound, upperBound, 2, 4, Boolean.TRUE)),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId).containsExactly(matching.getId());
    }


    @Test
    void findAll_shouldFilterByStalePendingProblemType() {
        Instant threshold = Instant.parse("2026-01-01T10:15:00Z");
        UUID oldPendingId = UUID.randomUUID();
        UUID freshPendingId = UUID.randomUUID();
        UUID processedId = UUID.randomUUID();
        insertOutboxEvent(oldPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:15:00Z"));
        insertOutboxEvent(freshPendingId, OutboxEventStatus.PENDING, Instant.parse("2026-01-01T10:15:01Z"));
        insertOutboxEvent(processedId, OutboxEventStatus.PROCESSED, Instant.parse("2026-01-01T10:00:00Z"));

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        OutboxEventProblemType.STALE_PENDING), threshold, 3),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactly(oldPendingId)
                .doesNotContain(freshPendingId, processedId);
    }

    @Test
    void findAll_shouldFilterByStaleFailedProblemType() {
        Instant threshold = Instant.parse("2026-01-01T10:15:00Z");
        UUID oldFailedId = UUID.randomUUID();
        UUID freshFailedId = UUID.randomUUID();
        UUID nullLastAttemptFailedId = UUID.randomUUID();
        UUID processedId = UUID.randomUUID();
        insertOutboxEventWithAttemptMetadata(oldFailedId, OutboxEventStatus.FAILED,
                Instant.parse("2026-01-01T10:00:00Z"), threshold, 1);
        insertOutboxEventWithAttemptMetadata(freshFailedId, OutboxEventStatus.FAILED,
                Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:15:01Z"), 1);
        insertOutboxEventWithAttemptMetadata(nullLastAttemptFailedId, OutboxEventStatus.FAILED,
                Instant.parse("2026-01-01T10:00:00Z"), null, 1);
        insertOutboxEventWithAttemptMetadata(processedId, OutboxEventStatus.PROCESSED,
                Instant.parse("2026-01-01T10:00:00Z"), threshold, 1);

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        OutboxEventProblemType.STALE_FAILED), threshold, 3),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactly(oldFailedId)
                .doesNotContain(freshFailedId, nullLastAttemptFailedId, processedId);
    }

    @Test
    void findAll_shouldFilterByHighAttemptFailedProblemTypeAndCombineWithAttemptsMax() {
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        UUID highFailedId = UUID.randomUUID();
        UUID lowFailedId = UUID.randomUUID();
        UUID processedHighAttemptsId = UUID.randomUUID();
        insertOutboxEventWithAttemptMetadata(highFailedId, OutboxEventStatus.FAILED, createdAt, createdAt, 3);
        insertOutboxEventWithAttemptMetadata(lowFailedId, OutboxEventStatus.FAILED, createdAt, createdAt, 2);
        insertOutboxEventWithAttemptMetadata(processedHighAttemptsId, OutboxEventStatus.PROCESSED, createdAt, createdAt, 3);

        Page<OutboxEvent> result = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                        null, null, null, null, null, null, null, null, null, null, null, null,
                        OutboxEventProblemType.HIGH_ATTEMPT_FAILED), createdAt, 3),
                PageRequest.of(0, 10));
        Page<OutboxEvent> contradictoryResult = outboxEventRepository.findAll(
                OutboxEventSpecifications.adminFilters(new OutboxEventAdminSearchCriteria(
                        null, null, null, null, null, null, null, null, null, null, 2, null,
                        OutboxEventProblemType.HIGH_ATTEMPT_FAILED), createdAt, 3),
                PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(OutboxEvent::getId)
                .containsExactly(highFailedId)
                .doesNotContain(lowFailedId, processedHighAttemptsId);
        assertThat(contradictoryResult.getContent()).isEmpty();
    }

    @Test
    void insert_shouldRejectMissingRequiredFields() {
        for (String requiredColumn : List.of(
                "id",
                "aggregate_type",
                "aggregate_id",
                "event_type",
                "payload",
                "status",
                "created_at",
                "attempts",
                "requeue_count")) {
            assertThatThrownBy(() -> jdbcTemplate.update(insertSqlWithNullValueFor(requiredColumn)))
                    .as("Expected database to reject null %s", requiredColumn)
                    .hasMessageContaining(requiredColumn);
        }
    }

    private void insertOutboxEvent(UUID eventId, OutboxEventStatus status, Instant createdAt) {
        UUID aggregateId = UUID.randomUUID();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO outbox_events (
                        id, aggregate_type, aggregate_id, event_type, payload, status, created_at, attempts
                    ) VALUES (
                        ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS timestamptz), ?
                    )
                    """);
            statement.setObject(1, eventId);
            statement.setString(2, "Order");
            statement.setObject(3, aggregateId);
            statement.setString(4, "TestEvent");
            statement.setString(5, "{\"orderId\":\"" + aggregateId + "\"}");
            statement.setString(6, status.name());
            statement.setString(7, createdAt.toString());
            statement.setInt(8, status == OutboxEventStatus.FAILED ? 1 : 0);
            return statement;
        });
    }


    private void insertOutboxEvent(
            UUID eventId,
            OutboxEventStatus status,
            String aggregateType,
            UUID aggregateId,
            String eventType) {
        insertOutboxEvent(eventId, status, aggregateType, aggregateId, eventType, Instant.parse("2026-01-01T10:00:00Z"));
    }

    private void insertOutboxEvent(
            UUID eventId,
            OutboxEventStatus status,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Instant createdAt) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO outbox_events (
                        id, aggregate_type, aggregate_id, event_type, payload, status, created_at, attempts
                    ) VALUES (
                        ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS timestamptz), ?
                    )
                    """);
            statement.setObject(1, eventId);
            statement.setString(2, aggregateType);
            statement.setObject(3, aggregateId);
            statement.setString(4, eventType);
            statement.setString(5, "{\"aggregateId\":\"" + aggregateId + "\"}");
            statement.setString(6, status.name());
            statement.setString(7, createdAt.toString());
            statement.setInt(8, status == OutboxEventStatus.FAILED ? 1 : 0);
            return statement;
        });
    }

    private void insertOutboxEventWithAttemptMetadata(
            UUID eventId,
            OutboxEventStatus status,
            Instant createdAt,
            Instant lastAttemptAt,
            int attempts) {
        UUID aggregateId = UUID.randomUUID();

        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO outbox_events (
                        id, aggregate_type, aggregate_id, event_type, payload, status, created_at, last_attempt_at, attempts
                    ) VALUES (
                        ?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?
                    )
                    """);
            statement.setObject(1, eventId);
            statement.setString(2, "Order");
            statement.setObject(3, aggregateId);
            statement.setString(4, "TestEvent");
            statement.setString(5, "{\"orderId\":\"" + aggregateId + "\"}");
            statement.setString(6, status.name());
            statement.setString(7, createdAt.toString());
            statement.setString(8, lastAttemptAt == null ? null : lastAttemptAt.toString());
            statement.setInt(9, attempts);
            return statement;
        });
    }

    private void updateCreatedAt(UUID eventId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE outbox_events SET created_at = CAST(? AS timestamptz) WHERE id = ?",
                createdAt.toString(),
                eventId);
    }

    private void updateLastAttemptAt(UUID eventId, Instant lastAttemptAt) {
        jdbcTemplate.update(
                "UPDATE outbox_events SET last_attempt_at = CAST(? AS timestamptz) WHERE id = ?",
                lastAttemptAt.toString(),
                eventId);
    }

    private String insertSqlWithNullValueFor(String columnName) {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        return """
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload, status, created_at, attempts, requeue_count
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s, %s
                )
                """.formatted(
                valueOrNull(columnName, "id", "'" + eventId + "'"),
                valueOrNull(columnName, "aggregate_type", "'Order'"),
                valueOrNull(columnName, "aggregate_id", "'" + aggregateId + "'"),
                valueOrNull(columnName, "event_type", "'TestEvent'"),
                valueOrNull(columnName, "payload", "'{\"orderId\":\"" + aggregateId + "\"}'::jsonb"),
                valueOrNull(columnName, "status", "'" + OutboxEventStatus.PENDING.name() + "'"),
                valueOrNull(columnName, "created_at", "CURRENT_TIMESTAMP"),
                valueOrNull(columnName, "attempts", "0"),
                valueOrNull(columnName, "requeue_count", "0"));
    }

    private OutboxEvent requeuedEvent(int requeueCount) {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        for (int i = 0; i < requeueCount; i++) {
            event.markFailed("boom");
            event.requeueForProcessing("admin@example.com");
        }
        return event;
    }

    private OutboxEvent attemptedEvent(int attempts) {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "TestEvent", "{\"id\":1}");
        for (int i = 0; i < attempts; i++) {
            event.markFailed("boom");
        }
        return event;
    }

    private String valueOrNull(String nullableColumnName, String currentColumnName, String value) {
        if (currentColumnName.equals(nullableColumnName)) {
            return "NULL";
        }
        return value;
    }
}
