package com.company.shop.module.order.outbox.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class OutboxEventRequeueNotAllowedException extends BusinessException {

    public OutboxEventRequeueNotAllowedException() {
        super(
                HttpStatus.CONFLICT,
                "OUTBOX_EVENT_REQUEUE_NOT_ALLOWED",
                "error.business.order.outboxEventRequeueNotAllowed",
                new Object[0],
                "Outbox event can be requeued only when it is FAILED.");
    }
}
