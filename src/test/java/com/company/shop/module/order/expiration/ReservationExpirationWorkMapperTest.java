package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.company.shop.common.model.BaseEntity;

class ReservationExpirationWorkMapperTest {

    private final ReservationExpirationWorkMapper mapper = Mappers.getMapper(ReservationExpirationWorkMapper.class);

    @Test
    void toDto_shouldMapAllResponseFields() throws Exception {
        UUID id = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant dueAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant nextAttemptAt = Instant.parse("2026-01-01T10:05:00Z");
        Instant claimUntil = Instant.parse("2026-01-01T10:10:00Z");
        Instant completedAt = Instant.parse("2026-01-01T10:15:00Z");
        Instant failedAt = Instant.parse("2026-01-01T10:20:00Z");
        Instant lastRecoveredAt = Instant.parse("2026-01-01T10:25:00Z");
        ReservationExpirationWork work = new ReservationExpirationWork(orderId, dueAt);
        setId(work, id);
        setField(work, "status", ReservationExpirationWorkStatus.FAILED);
        setField(work, "nextAttemptAt", nextAttemptAt);
        setField(work, "claimUntil", claimUntil);
        setField(work, "attempts", 4);
        setField(work, "lastError", "provider unavailable");
        setField(work, "completedAt", completedAt);
        setField(work, "failedAt", failedAt);
        setField(work, "recoveryCount", 2);
        setField(work, "lastRecoveredAt", lastRecoveredAt);
        setField(work, "lastRecoveredBy", "admin@example.com");

        ReservationExpirationWorkResponseDTO result = mapper.toDto(work);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.status()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
        assertThat(result.dueAt()).isEqualTo(dueAt);
        assertThat(result.nextAttemptAt()).isEqualTo(nextAttemptAt);
        assertThat(result.claimUntil()).isEqualTo(claimUntil);
        assertThat(result.attempts()).isEqualTo(4);
        assertThat(result.lastError()).isEqualTo("provider unavailable");
        assertThat(result.completedAt()).isEqualTo(completedAt);
        assertThat(result.failedAt()).isEqualTo(failedAt);
        assertThat(result.recoveryCount()).isEqualTo(2);
        assertThat(result.lastRecoveredAt()).isEqualTo(lastRecoveredAt);
        assertThat(result.lastRecoveredBy()).isEqualTo("admin@example.com");
    }

    @Test
    void toDto_shouldReturnNullForNullInput() {
        assertThat(mapper.toDto(null)).isNull();
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
