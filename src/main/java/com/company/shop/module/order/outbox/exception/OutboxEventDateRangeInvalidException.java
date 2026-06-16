package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventDateRangeInvalidException extends BusinessException {

    public OutboxEventDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "OUTBOX_EVENT_DATE_RANGE_INVALID",
                "error.business.order.outboxEventDateRangeInvalid",
                new Object[0],
                "createdFrom must be before or equal to createdTo.");
    }
}
