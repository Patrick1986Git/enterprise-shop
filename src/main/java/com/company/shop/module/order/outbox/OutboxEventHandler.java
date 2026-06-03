package com.company.shop.module.order.outbox;

public interface OutboxEventHandler {

    String eventType();

    void handle(OutboxEvent event);
}
