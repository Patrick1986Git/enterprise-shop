package com.company.shop.module.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.notification.entity.NotificationAdminActionType;

public record NotificationAdminActionLogResponseDTO(
        UUID id,
        UUID notificationId,
        NotificationAdminActionType actionType,
        String actorEmail,
        Instant createdAt,
        String details) {
}
