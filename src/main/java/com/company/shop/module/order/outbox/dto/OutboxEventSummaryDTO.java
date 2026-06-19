package com.company.shop.module.order.outbox.dto;

import java.time.Instant;

public record OutboxEventSummaryDTO(
        long pendingCount,
        long processedCount,
        long failedCount,
        long totalCount,
        long requeuedEventCount,
        long totalRequeueCount,
        Instant oldestPendingCreatedAt,
        Instant newestFailedCreatedAt,
        Instant newestAttemptAt,
        Instant newestProcessedAttemptAt,
        Instant newestFailedAttemptAt) {
}
