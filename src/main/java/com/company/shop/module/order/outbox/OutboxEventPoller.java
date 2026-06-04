package com.company.shop.module.order.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxEventPoller {

    private final OutboxEventProcessor outboxEventProcessor;
    private final OutboxProcessingProperties properties;

    public OutboxEventPoller(
            OutboxEventProcessor outboxEventProcessor,
            OutboxProcessingProperties properties) {
        this.outboxEventProcessor = outboxEventProcessor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.processing.fixed-delay:PT10S}")
    public void processPendingOutboxEvents() {
        if (!properties.enabled()) {
            return;
        }

        outboxEventProcessor.processPendingBatch(properties.batchSize());
    }
}
