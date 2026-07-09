package com.company.shop.module.order.outbox;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;

public record OrderPlacedEventPayload(
        UUID orderId,
        UUID userId,
        String userEmail,
        OrderStatus status,
        BigDecimal totalAmount,
        String createdAt,
        List<OrderPlacedEventItemPayload> items) {

    static OrderPlacedEventPayload from(Order order) {
        return new OrderPlacedEventPayload(
                order.getId(),
                order.getUserId(),
                order.getUserEmail(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt() == null ? null : order.getCreatedAt().toString(),
                order.getItems().stream()
                        .map(OrderPlacedEventItemPayload::from)
                        .toList());
    }
}
