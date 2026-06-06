package com.company.shop.module.notification.delivery;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryPoller {

    private final NotificationDeliveryProcessor notificationDeliveryProcessor;
    private final NotificationDeliveryProperties properties;

    public NotificationDeliveryPoller(
            NotificationDeliveryProcessor notificationDeliveryProcessor,
            NotificationDeliveryProperties properties) {
        this.notificationDeliveryProcessor = notificationDeliveryProcessor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.notification.delivery.fixed-delay:PT10S}")
    public void processPendingNotifications() {
        if (!properties.enabled()) {
            return;
        }

        notificationDeliveryProcessor.processPendingBatch(properties.batchSize());
    }
}
