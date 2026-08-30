package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ReservationExpirationWorkRepositoryIT extends PostgresContainerSupport {
    private static final Instant DUE_AT = Instant.parse("0001-01-01T00:00:00Z");

    private UUID firstWorkId;
    private UUID secondWorkId;
    private UUID thirdWorkId;
    private UUID firstOrderId;
    private UUID secondOrderId;
    private UUID thirdOrderId;

    @Autowired
    private ReservationExpirationWorkRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        long workIdBase = UUID.randomUUID().getLeastSignificantBits() & (Long.MAX_VALUE - 3);
        firstWorkId = new UUID(0, workIdBase);
        secondWorkId = new UUID(0, workIdBase + 1);
        thirdWorkId = new UUID(0, workIdBase + 2);
        firstOrderId = UUID.randomUUID();
        secondOrderId = UUID.randomUUID();
        thirdOrderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password) VALUES (?, ?, ?)",
                userId, "expiration-query-" + userId + "@example.com", "password");
        insertOrder(firstOrderId, userId, "first-" + firstOrderId + "@example.com");
        insertOrder(secondOrderId, userId, "second-" + secondOrderId + "@example.com");
        insertOrder(thirdOrderId, userId, "third-" + thirdOrderId + "@example.com");
        insertFailedWork(firstWorkId, firstOrderId, 4, 2, "admin@example.com");
        insertFailedWork(secondWorkId, secondOrderId, 3, 0, null);
        insertClaimedWork(thirdWorkId, thirdOrderId);
        entityManager.clear();
    }

    @Test
    void findAdminWork_shouldApplyExactFiltersAndReturnAllForNullFilters() {
        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id"));

        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.FAILED, null, pageable))
                .extracting(ReservationExpirationWork::getId)
                .contains(firstWorkId, secondWorkId);
        assertThat(repository.findAdminWork(null, secondOrderId, pageable))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(secondWorkId);
        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.FAILED, firstOrderId, pageable))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(firstWorkId);
        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.CLAIMED, firstOrderId, pageable))
                .isEmpty();
        assertThat(repository.findAdminWork(null, null, pageable))
                .extracting(ReservationExpirationWork::getId)
                .contains(firstWorkId, secondWorkId, thirdWorkId);
    }

    @Test
    void findAdminWork_shouldPageDeterministicallyAndRemainReadOnly() {
        PageRequest firstPage = PageRequest.of(0, 1, Sort.by(Sort.Order.asc("dueAt"), Sort.Order.asc("id")));
        PageRequest secondPage = PageRequest.of(1, 1, Sort.by(Sort.Order.asc("dueAt"), Sort.Order.asc("id")));
        Map<String, Object> before = operationalState(firstWorkId);

        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.FAILED, null, firstPage))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(firstWorkId);
        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.FAILED, null, secondPage))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(secondWorkId);
        entityManager.flush();

        assertThat(operationalState(firstWorkId)).isEqualTo(before);
    }

    private void insertOrder(UUID orderId, UUID userId, String email) {
        jdbcTemplate.update("""
                INSERT INTO orders (id, user_id, user_email, status, total_amount)
                VALUES (?, ?, ?, 'NEW', 10.00)
                """, orderId, userId, email);
    }

    private void insertFailedWork(UUID workId, UUID orderId, int attempts, int recoveryCount, String recoveredBy) {
        Instant recoveredAt = recoveryCount == 0 ? null : Instant.parse("2026-01-01T09:00:00Z");
        jdbcTemplate.update("""
                INSERT INTO reservation_expiration_work
                    (id, order_id, status, due_at, next_attempt_at, attempts, last_error, failed_at,
                     recovery_count, last_recovered_at, last_recovered_by)
                VALUES (?, ?, 'FAILED', ?, ?, ?, 'provider unavailable', ?, ?, ?, ?)
                """, workId, orderId, DUE_AT, DUE_AT, attempts, DUE_AT, recoveryCount, recoveredAt, recoveredBy);
    }

    private void insertClaimedWork(UUID workId, UUID orderId) {
        jdbcTemplate.update("""
                INSERT INTO reservation_expiration_work
                    (id, order_id, status, due_at, next_attempt_at, claim_token, claim_until, attempts, recovery_count)
                VALUES (?, ?, 'CLAIMED', ?, ?, ?, ?, 1, 0)
                """, workId, orderId, DUE_AT, DUE_AT, UUID.randomUUID(), DUE_AT.plusSeconds(300));
    }

    private Map<String, Object> operationalState(UUID workId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, attempts, claim_token, claim_until, recovery_count, last_recovered_at, last_recovered_by
                FROM reservation_expiration_work WHERE id = ?
                """, workId);
    }
}
