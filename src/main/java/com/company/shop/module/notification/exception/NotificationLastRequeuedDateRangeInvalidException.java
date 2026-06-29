package com.company.shop.module.notification.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationLastRequeuedDateRangeInvalidException extends BusinessException {

    public NotificationLastRequeuedDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_LAST_REQUEUED_DATE_RANGE_INVALID",
                "error.business.notification.lastRequeuedDateRangeInvalid",
                new Object[0],
                "lastRequeuedFrom must be before or equal to lastRequeuedTo.");
    }
}
