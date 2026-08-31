package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.company.shop.common.model.BaseEntity;

class ReservationExpirationAdminActionLogMapperTest {
    private final ReservationExpirationAdminActionLogMapper mapper =
            Mappers.getMapper(ReservationExpirationAdminActionLogMapper.class);

    @Test
    void toDto_shouldMapAllResponseFields() throws Exception {
        UUID id = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-31T12:00:00Z");
        ReservationExpirationAdminActionLog log = ReservationExpirationAdminActionLog.recovery(
                orderId, workId, ReservationExpirationAdminActionOutcome.REQUEUED,
                "admin@example.com", createdAt);
        setId(log, id);

        ReservationExpirationAdminActionLogResponseDTO result = mapper.toDto(log);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.workId()).isEqualTo(workId);
        assertThat(result.actionType()).isEqualTo(ReservationExpirationAdminActionType.RECOVERY);
        assertThat(result.outcome()).isEqualTo(ReservationExpirationAdminActionOutcome.REQUEUED);
        assertThat(result.actorEmail()).isEqualTo("admin@example.com");
        assertThat(result.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void toDto_shouldReturnNullForNullActionLog() {
        assertThat(mapper.toDto(null)).isNull();
    }

    private void setId(Object entity, UUID id) throws Exception {
        Field field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
