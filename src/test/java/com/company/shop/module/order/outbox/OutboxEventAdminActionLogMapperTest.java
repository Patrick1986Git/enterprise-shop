package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.company.shop.common.model.BaseEntity;

class OutboxEventAdminActionLogMapperTest {

    private final OutboxEventAdminActionLogMapper mapper = Mappers.getMapper(OutboxEventAdminActionLogMapper.class);

    @Test
    void toDto_shouldMapAllResponseFields() throws Exception {
        UUID id = UUID.randomUUID();
        UUID outboxEventId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        OutboxEventAdminActionLog log = OutboxEventAdminActionLog.requeue(outboxEventId, "admin@example.com");
        setId(log, id);
        setField(log, "createdAt", createdAt);
        setField(log, "details", "Requeued after failure");

        var result = mapper.toDto(log);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.outboxEventId()).isEqualTo(outboxEventId);
        assertThat(result.actionType()).isEqualTo(OutboxEventAdminActionType.REQUEUE);
        assertThat(result.actorEmail()).isEqualTo("admin@example.com");
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.details()).isEqualTo("Requeued after failure");
    }

    private void setId(Object entity, UUID id) throws Exception {
        Field field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
