package com.company.shop.module.order.outbox;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.OrderStatus;

@Component
public class OrderOutboxEventRecorder {

    private static final String ORDER_AGGREGATE_TYPE = "Order";
    private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderOutboxEventRecorder(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void recordOrderPlaced(Order order) {
        OrderPlacedPayload payload = OrderPlacedPayload.from(order);
        outboxEventRepository.save(OutboxEvent.pending(
                ORDER_AGGREGATE_TYPE,
                order.getId(),
                ORDER_PLACED_EVENT_TYPE,
                serialize(payload)));
    }

    private String serialize(OrderPlacedPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OrderPlaced outbox payload", ex);
        }
    }

    private record OrderPlacedPayload(
            UUID orderId,
            UUID userId,
            String userEmail,
            OrderStatus status,
            BigDecimal totalAmount,
            LocalDateTime createdAt,
            List<OrderPlacedItemPayload> items) {

        private static OrderPlacedPayload from(Order order) {
            return new OrderPlacedPayload(
                    order.getId(),
                    order.getUserId(),
                    order.getUserEmail(),
                    order.getStatus(),
                    order.getTotalAmount(),
                    order.getCreatedAt(),
                    order.getItems().stream()
                            .map(OrderPlacedItemPayload::from)
                            .toList());
        }
    }

    private record OrderPlacedItemPayload(
            UUID productId,
            String productName,
            String productSku,
            BigDecimal price,
            int quantity) {

        private static OrderPlacedItemPayload from(OrderItem item) {
            return new OrderPlacedItemPayload(
                    item.getProductId(),
                    item.getProductName(),
                    item.getProductSku(),
                    item.getPrice(),
                    item.getQuantity());
        }
    }
}
