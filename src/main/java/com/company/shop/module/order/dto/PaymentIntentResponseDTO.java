package com.company.shop.module.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload containing payment intent details needed by the client.")
public record PaymentIntentResponseDTO(
    @Schema(
            description = "Payment provider client secret for confirming the payment.",
            example = "payment_client_placeholder",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    String clientSecret,
    @Schema(
            description = "Payment provider publishable key safe for client-side use.",
            example = "publishable_key_placeholder",
            accessMode = Schema.AccessMode.READ_ONLY
    )
    String publishableKey
) {}
