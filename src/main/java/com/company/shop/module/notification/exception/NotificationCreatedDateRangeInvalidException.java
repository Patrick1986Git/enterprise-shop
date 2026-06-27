package com.company.shop.module.notification.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class NotificationCreatedDateRangeInvalidException extends BusinessException {

    public NotificationCreatedDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "NOTIFICATION_CREATED_DATE_RANGE_INVALID",
                "error.business.notification.createdDateRangeInvalid",
                new Object[0],
                "createdFrom must be before or equal to createdTo.");
    }
}
