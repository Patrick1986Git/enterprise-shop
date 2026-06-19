package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventLastAttemptDateRangeInvalidException extends BusinessException {

    public OutboxEventLastAttemptDateRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "OUTBOX_EVENT_LAST_ATTEMPT_DATE_RANGE_INVALID",
                "error.business.order.outboxEventLastAttemptDateRangeInvalid",
                new Object[0],
                "lastAttemptFrom must be before or equal to lastAttemptTo.");
    }
}
