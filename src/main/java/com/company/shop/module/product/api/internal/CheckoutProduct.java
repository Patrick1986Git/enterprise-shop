package com.company.shop.module.product.api.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutProduct(
        UUID id,
        String name,
        BigDecimal price
) {
}
