package com.company.shop.module.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.notification.entity.NotificationStatus;

public record NotificationResponseDTO(
        UUID id,
        String type,
        String recipient,
        String subject,
        String body,
        NotificationStatus status,
        UUID sourceEventId,
        Instant createdAt,
        Instant sentAt,
        int attempts,
        String lastError,
        Instant nextAttemptAt
) {
}
