package com.company.shop.module.notification.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationNotFoundException extends BusinessException {

    public NotificationNotFoundException(UUID notificationId) {
        super(HttpStatus.NOT_FOUND,
                "NOTIFICATION_NOT_FOUND",
                "error.business.notification.notFound",
                new Object[] {notificationId},
                "Notification not found: " + notificationId);
    }
}
