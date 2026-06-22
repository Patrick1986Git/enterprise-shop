package com.company.shop.module.order.outbox.dto;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.module.order.outbox.OutboxEventStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing outbox event details.")
public record OutboxEventResponseDTO(
        @Schema(description = "Unique outbox event identifier.", example = "66666666-6666-6666-6666-666666666666", accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @Schema(description = "Aggregate type associated with the event.", example = "Order", accessMode = Schema.AccessMode.READ_ONLY) String aggregateType,
        @Schema(description = "Aggregate identifier associated with the event.", example = "44444444-4444-4444-4444-444444444444", accessMode = Schema.AccessMode.READ_ONLY) UUID aggregateId,
        @Schema(description = "Domain event type.", example = "ORDER_CREATED", accessMode = Schema.AccessMode.READ_ONLY) String eventType,
        @Schema(description = "Current processing status.", example = "PENDING", accessMode = Schema.AccessMode.READ_ONLY) OutboxEventStatus status,
        @Schema(description = "Time when the event was created.", example = "2026-06-22T10:15:30Z", accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(description = "Time when the event was processed.", example = "2026-06-22T10:16:30Z", accessMode = Schema.AccessMode.READ_ONLY) Instant processedAt,
        @Schema(description = "Time of the last processing attempt.", example = "2026-06-22T10:16:00Z", accessMode = Schema.AccessMode.READ_ONLY) Instant lastAttemptAt,
        @Schema(description = "Number of processing attempts.", example = "1", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY) int attempts,
        @Schema(description = "Most recent processing error, when present.", example = "Temporary downstream error", accessMode = Schema.AccessMode.READ_ONLY) String lastError,
        @Schema(description = "Number of times the event was manually requeued.", example = "0", minimum = "0", accessMode = Schema.AccessMode.READ_ONLY) int requeueCount,
        @Schema(description = "Time when the event was last requeued.", example = "2026-06-22T11:00:00Z", accessMode = Schema.AccessMode.READ_ONLY) Instant lastRequeuedAt,
        @Schema(description = "Email of the administrator who last requeued the event.", example = "admin@example.com", accessMode = Schema.AccessMode.READ_ONLY) String lastRequeuedBy) {
}
