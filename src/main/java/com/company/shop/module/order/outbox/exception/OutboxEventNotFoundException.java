package com.company.shop.module.order.outbox.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventNotFoundException extends BusinessException {

    public OutboxEventNotFoundException(UUID id) {
        super(
                HttpStatus.NOT_FOUND,
                "OUTBOX_EVENT_NOT_FOUND",
                "error.business.order.outboxEventNotFound",
                new Object[] { id },
                "Outbox event not found: " + id);
    }
}
