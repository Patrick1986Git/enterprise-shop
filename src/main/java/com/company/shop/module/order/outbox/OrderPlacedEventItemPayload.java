package com.company.shop.module.order.outbox;

import java.math.BigDecimal;
import java.util.UUID;

import com.company.shop.module.order.entity.OrderItem;

public record OrderPlacedEventItemPayload(
        UUID productId,
        String productName,
        String productSku,
        BigDecimal price,
        int quantity) {

    static OrderPlacedEventItemPayload from(OrderItem item) {
        return new OrderPlacedEventItemPayload(
                item.getProductId(),
                item.getProductName(),
                item.getProductSku(),
                item.getPrice(),
                item.getQuantity());
    }
}
