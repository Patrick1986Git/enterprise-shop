package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest(properties = {
        "app.outbox.processing.retry-delay=PT1M",
        "app.outbox.processing.max-attempts=3"
})
@ActiveProfiles("test")
class OutboxEventProcessorTransactionIT extends PostgresContainerSupport {

    private static final String SUCCESS_EVENT_TYPE = "TransactionIsolationSuccessEvent";
    private static final String FAILING_EVENT_TYPE = "TransactionIsolationFailingEvent";
    private static final String CONCURRENT_EVENT_TYPE = "ConcurrentCoordinatorOverlapEvent";
    private static final String NON_RETRYABLE_EVENT_TYPE = "TransactionIsolationNonRetryableEvent";
    private static final String FINAL_RETRY_EVENT_TYPE = "TransactionIsolationFinalRetryEvent";

    @Autowired
    private OutboxEventProcessor outboxEventProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConcurrentCoordinatorOverlapHandler concurrentCoordinatorOverlapHandler;

    @BeforeEach
    void cleanOutboxEvents() {
        concurrentCoordinatorOverlapHandler.reset();
        jdbcTemplate.update("DELETE FROM outbox_event_admin_action_logs");
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    @Test
    void processPendingBatch_shouldIsolateEachEventTransactionAndRecordFailureIndependently() {
        OutboxEvent success = outboxEventRepository.saveAndFlush(
                OutboxEvent.pending("Test", UUID.randomUUID(), SUCCESS_EVENT_TYPE, "{}"));
        OutboxEvent failure = outboxEventRepository.saveAndFlush(
                OutboxEvent.pending("Test", UUID.randomUUID(), FAILING_EVENT_TYPE, "{}"));
        UUID successId = success.getId();
        UUID failureId = failure.getId();
        Instant beforeProcessing = Instant.now();

        OutboxEventProcessingResult result = outboxEventProcessor.processPendingBatch(10);

        assertThat(result.processedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);

        OutboxEvent processed = outboxEventRepository.findById(successId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(OutboxEventStatus.PROCESSED);
        assertThat(processed.getProcessedAt()).isNotNull();

        OutboxEvent retried = outboxEventRepository.findById(failureId).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(retried.getAttempts()).isEqualTo(1);
        assertThat(retried.getLastError()).isEqualTo("intentional transactional dependency failure");
        assertThat(retried.getNextAttemptAt()).isAfter(beforeProcessing);
        assertThat(retried.getProcessedAt()).isNull();
        assertThat(retried.getDeadLetterReason()).isNull();
    }

    @Test
    void processSelectedCandidatesConcurrently_shouldSkipOverlappingCandidatesAlreadyLockedOrProcessed() throws Exception {
        List<OutboxEvent> events = outboxEventRepository.saveAllAndFlush(List.of(
                OutboxEvent.pending("Test", UUID.randomUUID(), CONCURRENT_EVENT_TYPE, "{}"),
                OutboxEvent.pending("Test", UUID.randomUUID(), CONCURRENT_EVENT_TYPE, "{}"),
                OutboxEvent.pending("Test", UUID.randomUUID(), CONCURRENT_EVENT_TYPE, "{}")));
        List<UUID> eventIds = events.stream().map(OutboxEvent::getId).toList();
        CountDownLatch selectedByBothCoordinators = new CountDownLatch(2);
        CountDownLatch allowProcessing = new CountDownLatch(1);
        concurrentCoordinatorOverlapHandler.arm(new CountDownLatch(2));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ObservedCoordinatorResult> firstCoordinator = executor.submit(() -> processSelectedCandidates(
                    3, selectedByBothCoordinators, allowProcessing));
            Future<ObservedCoordinatorResult> secondCoordinator = executor.submit(() -> processSelectedCandidates(
                    3, selectedByBothCoordinators, allowProcessing));

            assertThat(selectedByBothCoordinators.await(10, TimeUnit.SECONDS)).isTrue();
            allowProcessing.countDown();

            ObservedCoordinatorResult firstResult = firstCoordinator.get(10, TimeUnit.SECONDS);
            ObservedCoordinatorResult secondResult = secondCoordinator.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.candidateIds()).containsExactlyElementsOf(secondResult.candidateIds());
            assertThat(firstResult.candidateIds()).containsExactlyInAnyOrderElementsOf(eventIds);

            int combinedProcessedCount = firstResult.processingResult().processedCount()
                    + secondResult.processingResult().processedCount();
            int combinedFailedCount = firstResult.processingResult().failedCount()
                    + secondResult.processingResult().failedCount();
            List<OutboxEvent> processedEvents = outboxEventRepository.findAllById(eventIds);
            Set<UUID> committedHandlerEventIds = Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT outbox_event_id FROM outbox_event_admin_action_logs "
                            + "WHERE action_type = 'CONCURRENT_TEST_HANDLED'",
                    UUID.class));

            assertThat(processedEvents).hasSize(eventIds.size());
            assertThat(processedEvents)
                    .extracting(OutboxEvent::getStatus)
                    .containsOnly(OutboxEventStatus.PROCESSED);
            assertThat(processedEvents)
                    .extracting(OutboxEvent::getAttempts)
                    .containsOnly(0);
            assertThat(processedEvents)
                    .extracting(OutboxEvent::getLastError)
                    .containsOnlyNulls();
            assertThat(processedEvents)
                    .extracting(OutboxEvent::getDeadLetterReason)
                    .containsOnlyNulls();
            assertThat(committedHandlerEventIds).containsExactlyInAnyOrderElementsOf(eventIds);
            assertThat(countCommittedHandlerInvocations()).isEqualTo(eventIds.size());
            assertThat(combinedProcessedCount).isEqualTo(eventIds.size());
            assertThat(combinedFailedCount).isZero();
        } finally {
            allowProcessing.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void processPendingBatch_shouldDeadLetterNonRetryableFailureImmediately() {
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                OutboxEvent.pending("Test", UUID.randomUUID(), NON_RETRYABLE_EVENT_TYPE, "{}"));

        OutboxEventProcessingResult result = outboxEventProcessor.processPendingBatch(10);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        OutboxEvent deadLetter = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(deadLetter.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(deadLetter.getAttempts()).isEqualTo(1);
        assertThat(deadLetter.getLastError()).isEqualTo("intentional non-retryable failure");
        assertThat(deadLetter.getDeadLetterReason())
                .isEqualTo(OutboxEventFailureRecorder.NON_RETRYABLE_FAILURE_REASON);
        assertThat(deadLetter.getNextAttemptAt()).isNull();
    }

    @Test
    void processPendingBatch_shouldDeadLetterRetryableFailureOnFinalAttempt() {
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                OutboxEvent.pending("Test", UUID.randomUUID(), FINAL_RETRY_EVENT_TYPE, "{}"));
        jdbcTemplate.update("UPDATE outbox_events SET attempts = 2 WHERE id = ?", event.getId());

        OutboxEventProcessingResult result = outboxEventProcessor.processPendingBatch(10);

        assertThat(result.processedCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        OutboxEvent deadLetter = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(deadLetter.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
        assertThat(deadLetter.getAttempts()).isEqualTo(3);
        assertThat(deadLetter.getLastError()).isEqualTo("intentional final retry failure");
        assertThat(deadLetter.getDeadLetterReason())
                .isEqualTo(OutboxEventFailureRecorder.MAX_ATTEMPTS_EXCEEDED_REASON);
        assertThat(deadLetter.getNextAttemptAt()).isNull();
    }

    private ObservedCoordinatorResult processSelectedCandidates(
            int batchSize,
            CountDownLatch selectedByBothCoordinators,
            CountDownLatch allowProcessing) throws InterruptedException {
        List<UUID> candidateIds = outboxEventRepository.findDuePendingCandidateIds(batchSize);
        selectedByBothCoordinators.countDown();
        assertThat(allowProcessing.await(10, TimeUnit.SECONDS)).isTrue();

        int processedCount = 0;
        int failedCount = 0;
        for (UUID candidateId : candidateIds) {
            OutboxEventProcessingOutcome outcome = processCandidateThroughWorker(candidateId);
            if (outcome == OutboxEventProcessingOutcome.PROCESSED) {
                processedCount++;
            } else if (outcome == OutboxEventProcessingOutcome.FAILED) {
                failedCount++;
            }
        }
        return new ObservedCoordinatorResult(
                candidateIds,
                new OutboxEventProcessingResult(processedCount, failedCount));
    }

    @Autowired
    private OutboxEventTransactionalWorker transactionalWorker;

    @Autowired
    private OutboxEventFailureRecorder failureRecorder;

    private OutboxEventProcessingOutcome processCandidateThroughWorker(UUID candidateId) {
        try {
            return transactionalWorker.processEvent(candidateId);
        } catch (NonRetryableOutboxEventException ex) {
            return failureRecorder.recordNonRetryableFailure(candidateId, ex);
        } catch (Exception ex) {
            return failureRecorder.recordRetryableFailure(candidateId, ex);
        }
    }

    private Integer countCommittedHandlerInvocations() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event_admin_action_logs WHERE action_type = 'CONCURRENT_TEST_HANDLED'",
                Integer.class);
    }

    private record ObservedCoordinatorResult(
            List<UUID> candidateIds,
            OutboxEventProcessingResult processingResult) {
    }

    @TestConfiguration
    static class OutboxTransactionIsolationTestConfig {

        @Bean
        ConcurrentCoordinatorOverlapHandler concurrentCoordinatorOverlapHandler(JdbcTemplate jdbcTemplate) {
            return new ConcurrentCoordinatorOverlapHandler(jdbcTemplate);
        }

        @Bean
        OutboxEventHandler transactionIsolationSuccessHandler() {
            return new OutboxEventHandler() {
                @Override
                public String eventType() {
                    return SUCCESS_EVENT_TYPE;
                }

                @Override
                public void handle(OutboxEvent event) {
                }
            };
        }

        @Bean
        FailingTransactionalDependency failingTransactionalDependency() {
            return new FailingTransactionalDependency();
        }

        @Bean
        OutboxEventHandler transactionIsolationNonRetryableHandler() {
            return new OutboxEventHandler() {
                @Override
                public String eventType() {
                    return NON_RETRYABLE_EVENT_TYPE;
                }

                @Override
                public void handle(OutboxEvent event) {
                    throw new NonRetryableOutboxEventException("intentional non-retryable failure");
                }
            };
        }

        @Bean
        OutboxEventHandler transactionIsolationFinalRetryHandler() {
            return new OutboxEventHandler() {
                @Override
                public String eventType() {
                    return FINAL_RETRY_EVENT_TYPE;
                }

                @Override
                public void handle(OutboxEvent event) {
                    throw new IllegalStateException("intentional final retry failure");
                }
            };
        }

        @Bean
        OutboxEventHandler transactionIsolationFailingHandler(FailingTransactionalDependency dependency) {
            return new OutboxEventHandler() {
                @Override
                public String eventType() {
                    return FAILING_EVENT_TYPE;
                }

                @Override
                public void handle(OutboxEvent event) {
                    dependency.markProcessedThenThrow(event);
                }
            };
        }
    }

    static class ConcurrentCoordinatorOverlapHandler implements OutboxEventHandler {

        private final JdbcTemplate jdbcTemplate;
        private CountDownLatch handlerEntries = new CountDownLatch(0);

        ConcurrentCoordinatorOverlapHandler(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public String eventType() {
            return CONCURRENT_EVENT_TYPE;
        }

        @Override
        public void handle(OutboxEvent event) {
            jdbcTemplate.update(
                    "INSERT INTO outbox_event_admin_action_logs "
                            + "(outbox_event_id, action_type, actor_email, details) VALUES (?, ?, ?, ?)",
                    event.getId(),
                    "CONCURRENT_TEST_HANDLED",
                    "outbox-concurrency-it@example.com",
                    "Committed only with the surrounding worker transaction.");
            handlerEntries.countDown();
            await(handlerEntries);
        }

        void arm(CountDownLatch handlerEntries) {
            this.handlerEntries = handlerEntries;
        }

        void reset() {
            this.handlerEntries = new CountDownLatch(0);
        }
    }

    static class FailingTransactionalDependency {

        @Transactional
        public void markProcessedThenThrow(OutboxEvent event) {
            event.markProcessed();
            throw new IllegalStateException("intentional transactional dependency failure");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
