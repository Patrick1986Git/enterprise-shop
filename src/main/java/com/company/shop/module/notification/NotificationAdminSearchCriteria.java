package com.company.shop.module.notification;

import java.time.Instant;

import com.company.shop.module.notification.entity.NotificationStatus;

public record NotificationAdminSearchCriteria(
        NotificationStatus status,
        String type,
        String recipient,
        String lastErrorContains,
        Boolean requeuedOnly,
        Integer attemptsMin,
        Integer attemptsMax,
        Instant lastAttemptFrom,
        Instant lastAttemptTo) {
}
