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
    private static final Instant DUE_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final UUID FIRST_WORK_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID SECOND_WORK_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID THIRD_WORK_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID FIRST_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SECOND_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID THIRD_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000203");

    @Autowired
    private ReservationExpirationWorkRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM reservation_expiration_work");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM users");
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        jdbcTemplate.update("INSERT INTO users (id, email, password) VALUES (?, ?, ?)",
                userId, "expiration-query@example.com", "password");
        insertOrder(FIRST_ORDER_ID, userId, "first@example.com");
        insertOrder(SECOND_ORDER_ID, userId, "second@example.com");
        insertOrder(THIRD_ORDER_ID, userId, "third@example.com");
        insertFailedWork(FIRST_WORK_ID, FIRST_ORDER_ID, 4, 2, "admin@example.com");
        insertFailedWork(SECOND_WORK_ID, SECOND_ORDER_ID, 3, 0, null);
        insertClaimedWork(THIRD_WORK_ID, THIRD_ORDER_ID);
        entityManager.clear();
    }

    @Test
    void findAdminWork_shouldApplyExactFiltersAndReturnAllForNullFilters() {
        PageRequest pageable = PageRequest.of(0, 20, Sort.by("id"));

        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.FAILED, null, pageable))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(FIRST_WORK_ID, SECOND_WORK_ID);
        assertThat(repository.findAdminWork(null, SECOND_ORDER_ID, pageable))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(SECOND_WORK_ID);
        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.FAILED, FIRST_ORDER_ID, pageable))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(FIRST_WORK_ID);
        assertThat(repository.findAdminWork(ReservationExpirationWorkStatus.CLAIMED, FIRST_ORDER_ID, pageable))
                .isEmpty();
        assertThat(repository.findAdminWork(null, null, pageable))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(FIRST_WORK_ID, SECOND_WORK_ID, THIRD_WORK_ID);
    }

    @Test
    void findAdminWork_shouldPageDeterministicallyAndRemainReadOnly() {
        PageRequest firstPage = PageRequest.of(0, 2, Sort.by(Sort.Order.asc("dueAt"), Sort.Order.asc("id")));
        PageRequest secondPage = PageRequest.of(1, 2, Sort.by(Sort.Order.asc("dueAt"), Sort.Order.asc("id")));
        Map<String, Object> before = operationalState(FIRST_WORK_ID);

        assertThat(repository.findAdminWork(null, null, firstPage))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(FIRST_WORK_ID, SECOND_WORK_ID);
        assertThat(repository.findAdminWork(null, null, secondPage))
                .extracting(ReservationExpirationWork::getId)
                .containsExactly(THIRD_WORK_ID);
        entityManager.flush();

        assertThat(operationalState(FIRST_WORK_ID)).isEqualTo(before);
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
