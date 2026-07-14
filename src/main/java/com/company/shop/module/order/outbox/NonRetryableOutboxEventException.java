package com.company.shop.module.order.outbox;

public class NonRetryableOutboxEventException extends RuntimeException {

    public NonRetryableOutboxEventException(String message) {
        super(message);
    }

    public NonRetryableOutboxEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
