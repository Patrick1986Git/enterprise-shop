package com.company.shop.module.notification.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationAttemptsRangeInvalidException extends BusinessException {

    public NotificationAttemptsRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_ATTEMPTS_RANGE_INVALID",
                "error.business.notification.attemptsRangeInvalid",
                new Object[0],
                "attemptsMin and attemptsMax must be non-negative, "
                        + "and attemptsMin must be less than or equal to attemptsMax.");
    }
}
