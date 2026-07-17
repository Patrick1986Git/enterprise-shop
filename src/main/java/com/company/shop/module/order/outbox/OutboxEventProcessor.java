package com.company.shop.module.order.outbox;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventTransactionalWorker transactionalWorker;
    private final OutboxEventFailureRecorder failureRecorder;

    public OutboxEventProcessor(
            OutboxEventRepository outboxEventRepository,
            OutboxEventTransactionalWorker transactionalWorker,
            OutboxEventFailureRecorder failureRecorder) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionalWorker = transactionalWorker;
        this.failureRecorder = failureRecorder;
    }

    public OutboxEventProcessingResult processPendingBatch(int batchSize) {
        List<UUID> candidateIds = outboxEventRepository.findDuePendingCandidateIds(batchSize);
        int processedCount = 0;
        int failedCount = 0;

        for (UUID candidateId : candidateIds) {
            OutboxEventProcessingOutcome outcome = processCandidate(candidateId);
            if (outcome == OutboxEventProcessingOutcome.PROCESSED) {
                processedCount++;
            } else if (outcome == OutboxEventProcessingOutcome.FAILED) {
                failedCount++;
            }
        }

        return new OutboxEventProcessingResult(processedCount, failedCount);
    }

    private OutboxEventProcessingOutcome processCandidate(UUID candidateId) {
        try {
            return transactionalWorker.processEvent(candidateId);
        } catch (NonRetryableOutboxEventException ex) {
            return failureRecorder.recordNonRetryableFailure(candidateId, ex);
        } catch (Exception ex) {
            return failureRecorder.recordRetryableFailure(candidateId, ex);
        }
    }
}
