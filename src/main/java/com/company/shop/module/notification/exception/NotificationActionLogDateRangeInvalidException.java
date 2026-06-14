package com.company.shop.module.notification.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationActionLogDateRangeInvalidException extends BusinessException {

    public NotificationActionLogDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_ACTION_LOG_DATE_RANGE_INVALID",
                "error.business.notification.actionLogDateRangeInvalid",
                new Object[0],
                "createdFrom must be before or equal to createdTo.");
    }
}
