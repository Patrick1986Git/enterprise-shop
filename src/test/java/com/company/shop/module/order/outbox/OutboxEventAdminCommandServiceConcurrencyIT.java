package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventRequeueNotAllowedException;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.security.CurrentUserProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OutboxEventAdminCommandServiceConcurrencyIT extends PostgresContainerSupport {

    @Autowired
    private OutboxEventAdminCommandService outboxEventAdminCommandService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventAdminActionLogRepository outboxEventAdminActionLogRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void setUp() {
        truncateTestData();
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");
    }

    @Test
    void requeueFailedEvent_shouldSerializeConcurrentRequeuesOnSingleOutboxRow() throws Exception {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        event.markDeadLetter("boom", "attempt limit reached");
        UUID eventId = outboxEventRepository.saveAndFlush(event).getId();

        CountDownLatch firstTransactionLockedEvent = new CountDownLatch(1);
        CountDownLatch allowFirstTransactionToCommit = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<Void> firstRequeue = executorService.submit(() -> {
                requeueInTransactionWhileHoldingLock(
                        eventId,
                        "first-admin@example.com",
                        firstTransactionLockedEvent,
                        allowFirstTransactionToCommit);
                return null;
            });

            assertThat(firstTransactionLockedEvent.await(5, TimeUnit.SECONDS))
                    .as("first transaction should lock the outbox row before the second requeue starts")
                    .isTrue();

            Future<Throwable> secondRequeue = executorService.submit(() -> {
                try {
                    outboxEventAdminCommandService.requeueFailedEvent(eventId);
                    return null;
                } catch (Throwable ex) {
                    return ex;
                }
            });

            assertThat(secondRequeue.isDone())
                    .as("second requeue should wait for the row lock instead of reading stale terminal state")
                    .isFalse();

            allowFirstTransactionToCommit.countDown();
            firstRequeue.get(5, TimeUnit.SECONDS);

            Throwable secondFailure = secondRequeue.get(5, TimeUnit.SECONDS);
            assertThat(secondFailure)
                    .isInstanceOf(OutboxEventRequeueNotAllowedException.class);
        } finally {
            allowFirstTransactionToCommit.countDown();
            executorService.shutdownNow();
            assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        OutboxEvent reloaded = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new OutboxEventNotFoundException(eventId));
        assertThat(reloaded.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(reloaded.getRequeueCount()).isEqualTo(1);
        assertThat(reloaded.getLastRequeuedBy()).isEqualTo("first-admin@example.com");
        assertThat(outboxEventAdminActionLogRepository.findByOutboxEventIdOrderByCreatedAtDesc(eventId))
                .hasSize(1)
                .allSatisfy(log -> assertThat(log.getActorEmail()).isEqualTo("first-admin@example.com"));
    }

    private void requeueInTransactionWhileHoldingLock(
            UUID eventId,
            String adminEmail,
            CountDownLatch eventLocked,
            CountDownLatch allowCommit) {
        transactionTemplate.executeWithoutResult(status -> {
            OutboxEvent event = outboxEventRepository.findByIdForManualRequeueUpdate(eventId)
                    .orElseThrow(() -> new OutboxEventNotFoundException(eventId));
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.DEAD_LETTER);
            eventLocked.countDown();
            try {
                assertThat(allowCommit.await(5, TimeUnit.SECONDS))
                        .as("first requeue transaction should be released by the test")
                        .isTrue();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting to commit first requeue transaction", ex);
            }
            event.requeueForProcessing(adminEmail);
            outboxEventAdminActionLogRepository.save(OutboxEventAdminActionLog.requeue(event.getId(), adminEmail));
        });
    }

    private void truncateTestData() {
        jdbcTemplate.execute("TRUNCATE TABLE outbox_event_admin_action_logs, outbox_events RESTART IDENTITY CASCADE");
    }
}
