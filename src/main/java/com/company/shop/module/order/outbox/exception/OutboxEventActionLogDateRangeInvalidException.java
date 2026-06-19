package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventActionLogDateRangeInvalidException extends BusinessException {

    public OutboxEventActionLogDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "OUTBOX_EVENT_ACTION_LOG_DATE_RANGE_INVALID",
                "error.business.order.outboxEventActionLogDateRangeInvalid",
                new Object[0],
                "createdFrom must be before or equal to createdTo.");
    }
}
