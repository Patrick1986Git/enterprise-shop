package com.company.shop.module.notification;

import java.time.Instant;

import com.company.shop.module.notification.entity.NotificationStatus;

public record NotificationAdminSearchCriteria(
        NotificationStatus status,
        NotificationDeliveryState deliveryState,
        String type,
        String recipient,
        String lastErrorContains,
        Boolean requeuedOnly,
        Integer attemptsMin,
        Integer attemptsMax,
        Instant lastAttemptFrom,
        Instant lastAttemptTo,
        Instant sentFrom,
        Instant sentTo) {

    public NotificationAdminSearchCriteria(
            NotificationStatus status,
            NotificationDeliveryState deliveryState,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax,
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        this(
                status,
                deliveryState,
                type,
                recipient,
                lastErrorContains,
                requeuedOnly,
                attemptsMin,
                attemptsMax,
                lastAttemptFrom,
                lastAttemptTo,
                null,
                null);
    }

    public NotificationAdminSearchCriteria(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax,
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        this(
                status,
                null,
                type,
                recipient,
                lastErrorContains,
                requeuedOnly,
                attemptsMin,
                attemptsMax,
                lastAttemptFrom,
                lastAttemptTo,
                null,
                null);
    }
}
