package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;

class OutboxEventMapperTest {

    private final OutboxEventMapper outboxEventMapper = Mappers.getMapper(OutboxEventMapper.class);

    @Test
    void toDto_shouldKeepListResponsePayloadFree() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending("Order", aggregateId, "OrderPlaced", "{\"orderId\":1}");
        setId(event, eventId);

        OutboxEventResponseDTO result = outboxEventMapper.toDto(event);

        assertThat(result.id()).isEqualTo(eventId);
        assertThat(result.aggregateType()).isEqualTo("Order");
        assertThat(result.aggregateId()).isEqualTo(aggregateId);
        assertThat(result.eventType()).isEqualTo("OrderPlaced");
        assertThat(result.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(result.createdAt()).isEqualTo(event.getCreatedAt());
        assertThat(result.processedAt()).isNull();
        assertThat(result.attempts()).isZero();
        assertThat(result.lastError()).isNull();
        assertThat(result.requeueCount()).isZero();
        assertThat(result.lastRequeuedAt()).isNull();
        assertThat(result.lastRequeuedBy()).isNull();
    }

    @Test
    void toDetailDto_shouldIncludePayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String payload = "{\"orderId\":1}";
        OutboxEvent event = OutboxEvent.pending("Order", aggregateId, "OrderPlaced", payload);
        setId(event, eventId);

        OutboxEventDetailResponseDTO result = outboxEventMapper.toDetailDto(event);

        assertThat(result.id()).isEqualTo(eventId);
        assertThat(result.aggregateType()).isEqualTo("Order");
        assertThat(result.aggregateId()).isEqualTo(aggregateId);
        assertThat(result.eventType()).isEqualTo("OrderPlaced");
        assertThat(result.payload()).isEqualTo(payload);
        assertThat(result.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(result.createdAt()).isEqualTo(event.getCreatedAt());
        assertThat(result.processedAt()).isNull();
        assertThat(result.attempts()).isZero();
        assertThat(result.lastError()).isNull();
        assertThat(result.requeueCount()).isZero();
        assertThat(result.lastRequeuedAt()).isNull();
        assertThat(result.lastRequeuedBy()).isNull();
    }

    private void setId(Object entity, UUID id) throws Exception {
        var field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
