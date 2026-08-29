package com.company.shop.module.notification.delivery;

import org.springframework.stereotype.Component;

import com.company.shop.module.notification.entity.Notification;

@Component
public class NotificationDeliveryProcessor {

    private final NotificationDeliveryTransactionalWorker transactionalWorker;
    private final NotificationSender notificationSender;

    public NotificationDeliveryProcessor(
            NotificationDeliveryTransactionalWorker transactionalWorker,
            NotificationSender notificationSender) {
        this.transactionalWorker = transactionalWorker;
        this.notificationSender = notificationSender;
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
                if (transactionalWorker.finalizeSuccess(notification.getId(), claim.token())) sentCount++;
            } catch (Exception ex) {
                if (transactionalWorker.finalizeFailure(notification.getId(), claim.token(), errorMessage(ex))) failedCount++;
            }
        }

        return new NotificationDeliveryResult(sentCount, failedCount);
    }

    private String errorMessage(Exception ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            return ex.getClass().getName();
        }
        return ex.getMessage();
    }
}
