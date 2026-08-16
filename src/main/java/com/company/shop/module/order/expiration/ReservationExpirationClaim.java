package com.company.shop.module.order.expiration;

import java.util.UUID;

public record ReservationExpirationClaim(UUID workId, UUID orderId, UUID claimToken) {}
