package com.company.shop.module.cart.api.internal;

import java.util.UUID;

public record CartCheckoutItem(
        UUID productId,
        int quantity
) {
}
