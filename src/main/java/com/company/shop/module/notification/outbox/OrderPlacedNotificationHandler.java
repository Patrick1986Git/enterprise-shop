package com.company.shop.module.notification.outbox;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.company.shop.module.notification.service.NotificationService;
import com.company.shop.module.order.outbox.OrderOutboxEventTypes;
import com.company.shop.module.order.outbox.OrderOutboxEventVersions;
import com.company.shop.module.order.outbox.OrderPlacedEventPayload;
import com.company.shop.module.order.outbox.OutboxEvent;
import com.company.shop.module.order.outbox.OutboxEventHandler;

@Component
public class OrderPlacedNotificationHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public OrderPlacedNotificationHandler(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return OrderOutboxEventTypes.ORDER_PLACED;
    }

    @Override
    public void handle(OutboxEvent event) {
        validateSupportedVersion(event);
        OrderPlacedEventPayload payload = parsePayload(event.getPayload());
        notificationService.createOrderPlacedNotification(
                payload.orderId(),
                payload.userEmail(),
                payload.totalAmount(),
                event.getId());
    }

    private void validateSupportedVersion(OutboxEvent event) {
        if (event.getEventVersion() != OrderOutboxEventVersions.ORDER_PLACED_V1) {
            throw new IllegalArgumentException(
                    "Unsupported OrderPlaced event version: " + event.getEventVersion()
                            + ". Supported version: " + OrderOutboxEventVersions.ORDER_PLACED_V1 + ".");
        }
    }

    private OrderPlacedEventPayload parsePayload(String payload) {
        try {
            OrderPlacedEventPayload eventPayload = objectMapper.readValue(payload, OrderPlacedEventPayload.class);
            validateRequiredPayloadData(eventPayload);
            return eventPayload;
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid OrderPlaced outbox payload: " + ex.getMessage(), ex);
        }
    }

    private void validateRequiredPayloadData(OrderPlacedEventPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload must be a JSON object");
        }
        if (payload.orderId() == null) {
            throw new IllegalArgumentException("Field 'orderId' is required");
        }
        if (payload.userEmail() == null || payload.userEmail().isBlank()) {
            throw new IllegalArgumentException("Field 'userEmail' is required");
        }
        if (payload.totalAmount() == null) {
            throw new IllegalArgumentException("Field 'totalAmount' is required");
        }
    }
}
