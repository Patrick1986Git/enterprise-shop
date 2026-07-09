package com.company.shop.module.order.outbox;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.company.shop.module.order.entity.Order;

@Component
public class OrderOutboxEventRecorder {

    private static final String ORDER_AGGREGATE_TYPE = "Order";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OrderOutboxEventRecorder(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void recordOrderPlaced(Order order) {
        OrderPlacedEventPayload payload = OrderPlacedEventPayload.from(order);
        outboxEventRepository.save(OutboxEvent.pending(
                ORDER_AGGREGATE_TYPE,
                order.getId(),
                OrderOutboxEventTypes.ORDER_PLACED,
                serialize(payload)));
    }

    private String serialize(OrderPlacedEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize OrderPlaced outbox payload", ex);
        }
    }
}
