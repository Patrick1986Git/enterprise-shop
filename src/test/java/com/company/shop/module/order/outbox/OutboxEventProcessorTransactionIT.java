package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

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

    @Autowired
    private OutboxEventProcessor outboxEventProcessor;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanOutboxEvents() {
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

    @TestConfiguration
    static class OutboxTransactionIsolationTestConfig {

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

    static class FailingTransactionalDependency {

        @Transactional
        public void markProcessedThenThrow(OutboxEvent event) {
            event.markProcessed();
            throw new IllegalStateException("intentional transactional dependency failure");
        }
    }
}
