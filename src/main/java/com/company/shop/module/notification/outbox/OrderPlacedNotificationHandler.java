package com.company.shop.module.notification.outbox;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.company.shop.module.notification.service.NotificationService;
import com.company.shop.module.order.outbox.OutboxEvent;
import com.company.shop.module.order.outbox.OutboxEventHandler;

@Component
public class OrderPlacedNotificationHandler implements OutboxEventHandler {

    private static final String ORDER_PLACED_EVENT_TYPE = "OrderPlaced";

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public OrderPlacedNotificationHandler(NotificationService notificationService, ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return ORDER_PLACED_EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        OrderPlacedNotificationPayload payload = parsePayload(event.getPayload());
        notificationService.createOrderPlacedNotification(
                payload.orderId(),
                payload.userEmail(),
                payload.totalAmount(),
                event.getId());
    }

    private OrderPlacedNotificationPayload parsePayload(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Payload must be a JSON object");
            }
            return new OrderPlacedNotificationPayload(
                    requiredUuid(root, "orderId"),
                    requiredText(root, "userEmail"),
                    requiredBigDecimal(root, "totalAmount"));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid OrderPlaced outbox payload: " + ex.getMessage(), ex);
        }
    }

    private UUID requiredUuid(JsonNode root, String fieldName) {
        String value = requiredText(root, fieldName);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Field '%s' must be a valid UUID".formatted(fieldName), ex);
        }
    }

    private BigDecimal requiredBigDecimal(JsonNode root, String fieldName) {
        JsonNode value = requiredField(root, fieldName);
        if (!value.isNumber() && !value.isTextual()) {
            throw new IllegalArgumentException("Field '%s' must be a number".formatted(fieldName));
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Field '%s' must be a valid decimal number".formatted(fieldName), ex);
        }
    }

    private String requiredText(JsonNode root, String fieldName) {
        JsonNode value = requiredField(root, fieldName);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Field '%s' is required".formatted(fieldName));
        }
        return value.asText();
    }

    private JsonNode requiredField(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Field '%s' is required".formatted(fieldName));
        }
        return value;
    }

    private record OrderPlacedNotificationPayload(UUID orderId, String userEmail, BigDecimal totalAmount) {
    }
}
