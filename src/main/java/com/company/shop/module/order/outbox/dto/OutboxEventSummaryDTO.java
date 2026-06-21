package com.company.shop.module.order.outbox.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record OutboxEventSummaryDTO(
        long pendingCount,
        long processedCount,
        long failedCount,
        long totalCount,
        long requeuedEventCount,
        long totalRequeueCount,
        @Schema(description = "Number of PENDING events older than the stale threshold by createdAt.")
        long stalePendingCount,
        @Schema(description = "Number of FAILED events whose lastAttemptAt is older than the stale threshold.")
        long staleFailedCount,
        @Schema(description = "Number of FAILED events with attempts greater than or equal to the high failed "
                + "attempts threshold.")
        long highAttemptFailedCount,
        @Schema(description = "Staleness threshold, in minutes, used for stale pending and failed event "
                + "summary counts.")
        long staleThresholdMinutes,
        @Schema(description = "Attempt count threshold used for high-attempt failed event summary counts.")
        int highFailedAttemptsThreshold,
        Instant oldestPendingCreatedAt,
        Instant newestFailedCreatedAt,
        Instant newestAttemptAt,
        Instant newestProcessedAttemptAt,
        Instant newestFailedAttemptAt) {
}
