package com.company.shop.module.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.notification.entity.NotificationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing notification delivery details.")
public record NotificationResponseDTO(
        @Schema(
                description = "Unique notification identifier.",
                example = "55555555-5555-5555-5555-555555555555",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        UUID id,
        @Schema(
                description = "Notification type.",
                example = "ORDER_CONFIRMATION",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String type,
        @Schema(
                description = "Notification recipient address or destination.",
                example = "user@example.com",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String recipient,
        @Schema(
                description = "Notification subject.",
                example = "Order confirmation",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String subject,
        @Schema(
                description = "Notification body content.",
                example = "Your order has been received.",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String body,
        @Schema(
                description = "Current notification delivery status.",
                example = "PENDING",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        NotificationStatus status,
        @Schema(
                description = "Source outbox event identifier, when available.",
                example = "66666666-6666-6666-6666-666666666666",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        UUID sourceEventId,
        @Schema(
                description = "Time when the notification was created.",
                example = "2026-06-22T10:15:30Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant createdAt,
        @Schema(
                description = "Time when the notification was sent.",
                example = "2026-06-22T10:16:30Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant sentAt,
        @Schema(
                description = "Number of delivery attempts.",
                example = "1",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        int attempts,
        @Schema(
                description = "Number of times the notification was manually requeued.",
                example = "0",
                minimum = "0",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        int requeueCount,
        @Schema(
                description = "Time when the notification was last requeued.",
                example = "2026-06-22T11:00:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant lastRequeuedAt,
        @Schema(
                description = "Email of the administrator who last requeued the notification.",
                example = "admin@example.com",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String lastRequeuedBy,
        @Schema(
                description = "Most recent delivery error message, when present.",
                example = "Temporary provider error",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        String lastError,
        @Schema(
                description = "Time of the last delivery attempt.",
                example = "2026-06-22T10:16:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant lastAttemptAt,
        @Schema(
                description = "Next scheduled delivery attempt time.",
                example = "2026-06-22T10:20:00Z",
                accessMode = Schema.AccessMode.READ_ONLY
        )
        Instant nextAttemptAt
) { }
