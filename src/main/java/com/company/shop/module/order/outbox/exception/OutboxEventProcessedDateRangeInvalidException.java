package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventProcessedDateRangeInvalidException extends BusinessException {

    public OutboxEventProcessedDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "OUTBOX_EVENT_PROCESSED_DATE_RANGE_INVALID",
                "error.business.order.outboxEventProcessedDateRangeInvalid",
                new Object[0],
                "processedFrom must be before or equal to processedTo.");
    }
}
