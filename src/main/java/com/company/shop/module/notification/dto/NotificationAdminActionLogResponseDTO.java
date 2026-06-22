package com.company.shop.module.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.notification.entity.NotificationAdminActionType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing a notification admin action log entry.")
public record NotificationAdminActionLogResponseDTO(
        @Schema(
                description = "Unique log entry identifier.",
                example = "77777777-7777-7777-7777-777777777777",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        UUID id,
        @Schema(
                description = "Notification identifier affected by the admin action.",
                example = "55555555-5555-5555-5555-555555555555",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        UUID notificationId,
        @Schema(
                description = "Type of admin action performed.",
                example = "REQUEUE",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        NotificationAdminActionType actionType,
        @Schema(
                description = "Email of the administrator who performed the action.",
                example = "admin@example.com",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String actorEmail,
        @Schema(
                description = "Time when the admin action was recorded.",
                example = "2026-06-22T11:00:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant createdAt,
        @Schema(
                description = "Additional action details.",
                example = "Manual requeue requested after provider recovery.",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String details) { }
