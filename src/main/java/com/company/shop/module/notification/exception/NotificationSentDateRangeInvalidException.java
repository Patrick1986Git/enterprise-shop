package com.company.shop.module.notification.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationSentDateRangeInvalidException extends BusinessException {

    public NotificationSentDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_SENT_DATE_RANGE_INVALID",
                "error.business.notification.sentDateRangeInvalid",
                new Object[0],
                "sentFrom must be before or equal to sentTo.");
    }
}
