package com.company.shop.module.cart.api.internal;

import java.util.List;
import java.util.UUID;

public record CartCheckoutSnapshot(
        UUID cartId,
        List<CartCheckoutItem> items
) {
    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }
}
