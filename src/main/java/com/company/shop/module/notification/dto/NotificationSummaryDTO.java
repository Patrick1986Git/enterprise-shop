package com.company.shop.module.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationSummaryDTO(
        @Schema(description = "Number of notifications currently waiting for delivery.")
        long pendingCount,
        @Schema(description = "Number of notifications successfully sent.")
        long sentCount,
        @Schema(description = "Number of notifications that failed delivery.")
        long failedCount,
        @Schema(description = "Number of pending notifications whose scheduled delivery time is due.")
        long duePendingCount,
        @Schema(description = "Number of pending notifications scheduled for future delivery.")
        long scheduledPendingCount,
        @Schema(description = "Number of notifications that have been manually requeued at least once.")
        long requeuedNotificationCount,
        @Schema(description = "Total number of manual notification requeue actions recorded.")
        long totalRequeueCount
) {
}
