package com.company.shop.module.order.outbox.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.order.outbox.OutboxEventAdminActionType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing an outbox event admin action log entry.")
public record OutboxEventAdminActionLogResponseDTO(
        @Schema(description = "Unique log entry identifier.", example = "77777777-7777-7777-7777-777777777777", accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @Schema(description = "Outbox event identifier affected by the admin action.", example = "66666666-6666-6666-6666-666666666666", accessMode = Schema.AccessMode.READ_ONLY) UUID outboxEventId,
        @Schema(description = "Type of admin action performed.", example = "REQUEUE", accessMode = Schema.AccessMode.READ_ONLY) OutboxEventAdminActionType actionType,
        @Schema(description = "Email of the administrator who performed the action.", example = "admin@example.com", accessMode = Schema.AccessMode.READ_ONLY) String actorEmail,
        @Schema(description = "Time when the admin action was recorded.", example = "2026-06-22T11:00:00Z", accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(description = "Additional action details.", example = "Manual requeue requested after downstream recovery.", accessMode = Schema.AccessMode.READ_ONLY) String details) {
}
