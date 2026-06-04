package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.company.shop.common.model.AuditableEntity;
import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.OrderStatus;

@ExtendWith(MockitoExtension.class)
class OrderOutboxEventRecorderTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ObjectMapper objectMapper;
    private OrderOutboxEventRecorder recorder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        recorder = new OrderOutboxEventRecorder(outboxEventRepository, objectMapper);
    }

    @Test
    void recordOrderPlaced_shouldPersistPendingOrderPlacedEventWithSnapshotPayload() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 5, 31, 10, 15, 30);
        Order order = new Order(userId, "john@example.com");
        order.addItem(new OrderItem(productId, "Product", "SKU-1", 2, BigDecimal.valueOf(12.50)));
        setEntityId(order, orderId);
        setCreatedAt(order, createdAt);
        when(outboxEventRepository.save(any(OutboxEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        recorder.recordOrderPlaced(order);

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(eventCaptor.capture());
        OutboxEvent event = eventCaptor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(orderId);
        assertThat(event.getEventType()).isEqualTo("OrderPlaced");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(payload.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(payload.get("userEmail").asText()).isEqualTo("john@example.com");
        assertThat(payload.get("status").asText()).isEqualTo(OrderStatus.NEW.name());
        assertThat(payload.get("totalAmount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(payload.get("createdAt").asText()).isEqualTo("2026-05-31T10:15:30");
        assertThat(payload.get("items")).hasSize(1);
        assertThat(payload.get("items").get(0).get("productId").asText()).isEqualTo(productId.toString());
        assertThat(payload.get("items").get(0).get("productName").asText()).isEqualTo("Product");
        assertThat(payload.get("items").get(0).get("productSku").asText()).isEqualTo("SKU-1");
        assertThat(payload.get("items").get(0).get("price").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    }

    private void setEntityId(Object entity, UUID id) throws Exception {
        Field field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setCreatedAt(Object entity, LocalDateTime createdAt) throws Exception {
        Field field = AuditableEntity.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(entity, createdAt);
    }
}
