package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventVersionInvalidException extends BusinessException {

    public OutboxEventVersionInvalidException() {
        super(
                HttpStatus.BAD_REQUEST,
                "OUTBOX_EVENT_VERSION_INVALID",
                "error.business.order.outboxEventVersionInvalid",
                new Object[0],
                "eventVersion must be greater than or equal to 1.");
    }
}
