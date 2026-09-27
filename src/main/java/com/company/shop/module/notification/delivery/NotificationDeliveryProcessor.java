package com.company.shop.module.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.company.shop.module.notification.entity.Notification;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class NotificationDeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryProcessor.class);

    private final NotificationDeliveryTransactionalWorker transactionalWorker;
    private final NotificationSender notificationSender;
    private final MeterRegistry meters;

    public NotificationDeliveryProcessor(
            NotificationDeliveryTransactionalWorker transactionalWorker,
            NotificationSender notificationSender,
            MeterRegistry meters) {
        this.transactionalWorker = transactionalWorker;
        this.notificationSender = notificationSender;
        this.meters = meters;
    }

    public NotificationDeliveryResult processPendingBatch(int batchSize) {
        int sentCount = 0;
        int failedCount = 0;

        for (int processedCount = 0; processedCount < batchSize; processedCount++) {
            ClaimedNotification claim = transactionalWorker.claimBatch(1).stream().findFirst().orElse(null);
            if (claim == null) {
                break;
            }
            Notification notification = claim.notification();
            try {
                notificationSender.send(notification);
                if (transactionalWorker.finalizeSuccess(notification.getId(), claim.token())) {
                    sentCount++;
                    recordOutcome("provider_returned_success_finalized");
                } else {
                    recordOutcome("provider_returned_success_finalization_rejected");
                    log.warn("Notification provider returned successfully but claim finalization was rejected; "
                            + "external outcome may have occurred and the notification may be retried: notificationId={}, claimToken={}",
                            notification.getId(), claim.token());
                }
            } catch (Exception ex) {
                recordOutcome("provider_call_exception");
                log.warn("Notification provider call threw; provider acceptance is not known: notificationId={}, claimToken={}, exceptionType={}",
                        notification.getId(), claim.token(), ex.getClass().getName());
                if (transactionalWorker.finalizeFailure(notification.getId(), claim.token(), errorMessage(ex))) {
                    failedCount++;
                    recordOutcome("provider_exception_finalized");
                } else {
                    recordOutcome("provider_exception_finalization_rejected");
                    log.warn("Notification provider exception finalization was rejected; claim ownership changed and external outcome remains unknown: "
                            + "notificationId={}, claimToken={}", notification.getId(), claim.token());
                }
            }
        }

        return new NotificationDeliveryResult(sentCount, failedCount);
    }

    private void recordOutcome(String outcome) {
        meters.counter("shop.notification.delivery.attempt.total", "outcome", outcome).increment();
    }

    private String errorMessage(Exception ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex.getClass().getName();
        }
        return ex.getMessage();
    }
}
