package com.company.shop.module.order.outbox.dto;

import java.time.Instant;

public record OutboxEventSummaryDTO(
        long pendingCount,
        long processedCount,
        long failedCount,
        long totalCount,
        long requeuedEventCount,
        long totalRequeueCount,
        long stalePendingCount,
        long staleFailedCount,
        long highAttemptFailedCount,
        long staleThresholdMinutes,
        int highFailedAttemptsThreshold,
        Instant oldestPendingCreatedAt,
        Instant newestFailedCreatedAt,
        Instant newestAttemptAt,
        Instant newestProcessedAttemptAt,
        Instant newestFailedAttemptAt) {
}
