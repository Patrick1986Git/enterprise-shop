package com.company.shop.module.notification.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationLastAttemptDateRangeInvalidException extends BusinessException {

    public NotificationLastAttemptDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_LAST_ATTEMPT_DATE_RANGE_INVALID",
                "error.business.notification.lastAttemptDateRangeInvalid",
                new Object[0],
                "lastAttemptFrom must be before or equal to lastAttemptTo.");
    }
}
