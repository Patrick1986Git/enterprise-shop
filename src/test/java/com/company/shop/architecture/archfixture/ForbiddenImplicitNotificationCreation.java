package com.company.shop.architecture.archfixture;

import java.util.UUID;

import com.company.shop.module.notification.entity.Notification;

public class ForbiddenImplicitNotificationCreation {

    public Notification create(UUID sourceEventId) {
        return Notification.pending("TYPE", "recipient@example.com", "Subject", "Body", sourceEventId);
    }
}
