package com.company.shop.module.order.outbox.dto;

import java.time.Instant;

public record OutboxEventSummaryDTO(
        long pendingCount,
        long processedCount,
        long failedCount,
        long totalCount,
        Instant oldestPendingCreatedAt,
        Instant newestFailedCreatedAt) {
}
