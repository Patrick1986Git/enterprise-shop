package com.company.shop.module.notification.delivery;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.repository.NotificationRepository;

@Component
public class NotificationDeliveryProcessor {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;
    private final NotificationDeliveryProperties properties;

    public NotificationDeliveryProcessor(
            NotificationRepository notificationRepository,
            NotificationSender notificationSender,
            NotificationDeliveryProperties properties) {
        this.notificationRepository = notificationRepository;
        this.notificationSender = notificationSender;
        this.properties = properties;
    }

    @Transactional
    public NotificationDeliveryResult processPendingBatch(int batchSize) {
        List<Notification> pendingNotifications = notificationRepository.findPendingBatchForUpdate(batchSize);
        int sentCount = 0;
        int failedCount = 0;

        for (Notification notification : pendingNotifications) {
            try {
                notificationSender.send(notification);
                notification.markSent();
                sentCount++;
            } catch (Exception ex) {
                Instant nextAttemptAt = Instant.now().plus(properties.retryDelay());
                notification.markDeliveryAttemptFailed(errorMessage(ex), properties.maxAttempts(), nextAttemptAt);
                failedCount++;
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
