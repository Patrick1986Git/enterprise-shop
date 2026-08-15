package com.company.shop.module.product.api.internal;

import java.util.UUID;

public record ReservedInventoryItem(UUID productId, int quantity) {
}
