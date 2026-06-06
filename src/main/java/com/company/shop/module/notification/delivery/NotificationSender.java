package com.company.shop.module.notification.delivery;

import com.company.shop.module.notification.entity.Notification;

public interface NotificationSender {

    void send(Notification notification);
}
