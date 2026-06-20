package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventAttemptsRangeInvalidException extends BusinessException {

    public OutboxEventAttemptsRangeInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "OUTBOX_EVENT_ATTEMPTS_RANGE_INVALID",
                "error.business.order.outboxEventAttemptsRangeInvalid",
                new Object[0],
                "attemptsMin and attemptsMax must be non-negative and attemptsMin must be less than or equal to attemptsMax.");
    }
}
