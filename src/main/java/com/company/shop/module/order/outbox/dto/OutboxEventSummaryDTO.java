package com.company.shop.module.order.outbox.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing outbox event processing summary metrics.")
public record OutboxEventSummaryDTO(
        @Schema(
                description = "Number of events waiting for processing.",
                example = "5",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long pendingCount,
        @Schema(
                description = "Number of successfully processed events.",
                example = "120",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long processedCount,
        @Schema(
                description = "Number of failed events.",
                example = "2",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long failedCount,
        @Schema(
                description = "Number of dead-lettered events requiring operational review.",
                example = "1",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long deadLetterCount,
        @Schema(
                description = "Total number of outbox events.",
                example = "127",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long totalCount,
        @Schema(
                description = "Number of events manually requeued at least once.",
                example = "1",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long requeuedEventCount,
        @Schema(
                description = "Total number of manual outbox event requeue actions recorded.",
                example = "1",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long totalRequeueCount,
        @Schema(
                description = "Number of PENDING events older than the stale threshold by createdAt.",
                example = "1",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long stalePendingCount,
        @Schema(
                description = "Number of FAILED events whose lastAttemptAt is older than the stale threshold.",
                example = "1",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long staleFailedCount,
        @Schema(
                description = "Number of FAILED events with attempts greater than or equal to the high failed attempts threshold.",
                example = "1",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long highAttemptFailedCount,
        @Schema(
                description = "Staleness threshold in minutes used for stale pending and failed event summary counts.",
                example = "30",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        long staleThresholdMinutes,
        @Schema(
                description = "Attempt count threshold used for high-attempt failed event summary counts.",
                example = "3",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        int highFailedAttemptsThreshold,
        @Schema(
                description = "Oldest pending event creation time.",
                example = "2026-06-22T09:00:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant oldestPendingCreatedAt,
        @Schema(
                description = "Newest failed event creation time.",
                example = "2026-06-22T10:00:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant newestFailedCreatedAt,
        @Schema(
                description = "Newest processing attempt time.",
                example = "2026-06-22T10:16:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant newestAttemptAt,
        @Schema(
                description = "Newest successful processing attempt time.",
                example = "2026-06-22T10:16:30Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant newestProcessedAttemptAt,
        @Schema(
                description = "Newest failed processing attempt time.",
                example = "2026-06-22T10:17:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant newestFailedAttemptAt,
        @Schema(
                description = "Oldest dead-lettered event creation time.",
                example = "2026-06-22T08:00:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant oldestDeadLetterCreatedAt,
        @Schema(
                description = "Newest dead-lettered processing attempt time.",
                example = "2026-06-22T10:18:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant newestDeadLetterAttemptAt) {
}
