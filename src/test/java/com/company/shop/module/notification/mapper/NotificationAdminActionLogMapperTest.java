package com.company.shop.module.notification.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.notification.entity.NotificationAdminActionLog;
import com.company.shop.module.notification.entity.NotificationAdminActionType;

class NotificationAdminActionLogMapperTest {

    private final NotificationAdminActionLogMapper mapper = Mappers.getMapper(NotificationAdminActionLogMapper.class);

    @Test
    void toDto_shouldMapAllResponseFields() throws Exception {
        UUID id = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        NotificationAdminActionLog log = NotificationAdminActionLog.requeue(notificationId, "admin@example.com");
        setId(log, id);
        setField(log, "createdAt", createdAt);

        var result = mapper.toDto(log);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.notificationId()).isEqualTo(notificationId);
        assertThat(result.actionType()).isEqualTo(NotificationAdminActionType.REQUEUE);
        assertThat(result.actorEmail()).isEqualTo("admin@example.com");
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.details()).isEqualTo("Manual requeue requested for failed notification.");
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
