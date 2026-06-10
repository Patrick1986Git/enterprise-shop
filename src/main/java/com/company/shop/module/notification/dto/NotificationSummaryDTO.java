package com.company.shop.module.notification.dto;

public record NotificationSummaryDTO(
        long pendingCount,
        long sentCount,
        long failedCount,
        long duePendingCount,
        long scheduledPendingCount
) {
}
