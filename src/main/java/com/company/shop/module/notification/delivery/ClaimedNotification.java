package com.company.shop.module.notification.delivery;

import java.util.UUID;
import com.company.shop.module.notification.entity.Notification;

record ClaimedNotification(Notification notification, UUID token) {
}
