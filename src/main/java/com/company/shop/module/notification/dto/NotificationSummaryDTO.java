package com.company.shop.module.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing notification delivery summary metrics.")
public record NotificationSummaryDTO(
        @Schema(description = "Number of notifications currently waiting for delivery.", example = "5", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        long pendingCount,
        @Schema(description = "Number of notifications successfully sent.", example = "120", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        long sentCount,
        @Schema(description = "Number of notifications that failed delivery.", example = "2", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        long failedCount,
        @Schema(description = "Number of pending notifications whose scheduled delivery time is due.", example = "3", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        long duePendingCount,
        @Schema(description = "Number of pending notifications scheduled for future delivery.", example = "2", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        long scheduledPendingCount,
        @Schema(description = "Number of notifications that have been manually requeued at least once.", example = "1", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        long requeuedNotificationCount,
        @Schema(description = "Total number of manual notification requeue actions recorded.", example = "1", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY)
        long totalRequeueCount
) {
}
