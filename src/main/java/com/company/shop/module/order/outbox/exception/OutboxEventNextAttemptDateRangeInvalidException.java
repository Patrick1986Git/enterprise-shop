package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventNextAttemptDateRangeInvalidException extends BusinessException {

    public OutboxEventNextAttemptDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "OUTBOX_EVENT_NEXT_ATTEMPT_DATE_RANGE_INVALID",
                "error.business.order.outboxEventNextAttemptDateRangeInvalid",
                new Object[0],
                "nextAttemptFrom must be before or equal to nextAttemptTo.");
    }
}
