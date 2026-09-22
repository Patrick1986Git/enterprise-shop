package com.company.shop.module.notification.delivery;

import com.company.shop.module.notification.entity.Notification;

public class NoopNotificationSender implements NotificationSender {

    @Override
    public void send(Notification notification) {
        throw new IllegalStateException("Notification delivery transport is not configured");
    }
}
