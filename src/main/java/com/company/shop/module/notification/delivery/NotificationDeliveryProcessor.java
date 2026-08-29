package com.company.shop.module.notification.delivery;

import java.util.List;

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
        List<ClaimedNotification> pendingNotifications = transactionalWorker.claimBatch(batchSize);
        int sentCount = 0;
        int failedCount = 0;

        for (ClaimedNotification claim : pendingNotifications) {
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
