package com.company.shop.module.order.outbox;

public record OutboxEventProcessingResult(int processedCount, int failedCount) {
}
