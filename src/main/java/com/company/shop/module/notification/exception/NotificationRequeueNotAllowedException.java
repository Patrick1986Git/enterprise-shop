package com.company.shop.module.notification.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationRequeueNotAllowedException extends BusinessException {

    public NotificationRequeueNotAllowedException() {
        super(
                HttpStatus.CONFLICT,
                "NOTIFICATION_REQUEUE_NOT_ALLOWED",
                "error.business.notification.requeueNotAllowed",
                new Object[0],
                "Notification can be requeued only when it is FAILED.");
    }
}
