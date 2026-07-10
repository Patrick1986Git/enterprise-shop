package com.company.shop.module.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.notification.service.NotificationService;
import com.company.shop.module.order.outbox.OrderOutboxEventTypes;
import com.company.shop.module.order.outbox.OutboxEvent;

@ExtendWith(MockitoExtension.class)
class OrderPlacedNotificationHandlerTest {

    @Mock
    private NotificationService notificationService;

    private OrderPlacedNotificationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OrderPlacedNotificationHandler(notificationService, new ObjectMapper());
    }

    @Test
    void eventType_shouldReturnOrderPlaced() {
        assertThat(handler.eventType()).isEqualTo(OrderOutboxEventTypes.ORDER_PLACED);
    }

    @Test
    void handle_shouldCreatePendingNotificationFromLegacyRawPayloadWithoutEnvelope() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        OutboxEvent event = orderPlacedEvent(eventId, """
                {
                  "orderId": "%s",
                  "userId": "%s",
                  "userEmail": "customer@example.com",
                  "status": "NEW",
                  "totalAmount": 42.50,
                  "createdAt": "2026-05-31T10:15:30",
                  "items": [
                    {
                      "productId": "%s",
                      "productName": "Product",
                      "productSku": "SKU-1",
                      "price": 12.50,
                      "quantity": 2
                    }
                  ]
                }
                """.formatted(orderId, UUID.randomUUID(), UUID.randomUUID()));

        handler.handle(event);

        verify(notificationService).createOrderPlacedNotification(
                eq(orderId),
                eq("customer@example.com"),
                argThat(amount -> amount.compareTo(new BigDecimal("42.50")) == 0),
                eq(eventId));
    }

    @Test
    void handle_shouldFailClearlyWhenPayloadIsInvalidJson() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), OrderOutboxEventTypes.ORDER_PLACED, "not-json");

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid OrderPlaced outbox payload");
        verifyNoInteractions(notificationService);
    }

    @Test
    void handle_shouldFailClearlyWhenRequiredPayloadDataIsMissing() {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), OrderOutboxEventTypes.ORDER_PLACED, """
                {
                  "orderId": "%s",
                  "totalAmount": 42.50
                }
                """.formatted(UUID.randomUUID()));

        assertThatThrownBy(() -> handler.handle(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid OrderPlaced outbox payload")
                .hasMessageContaining("userEmail");
        verifyNoInteractions(notificationService);
    }

    private OutboxEvent orderPlacedEvent(UUID eventId, String payload) throws Exception {
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), OrderOutboxEventTypes.ORDER_PLACED, payload);
        setId(event, eventId);
        return event;
    }

    private void setId(Object entity, UUID id) throws Exception {
        var field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
