package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ReservationExpirationAdminActionLogRepositoryIT extends PostgresContainerSupport {
    private static final Instant FIRST_AT = Instant.parse("2026-08-31T10:00:00Z");
    private static final Instant SECOND_AT = Instant.parse("2026-08-31T11:00:00Z");

    @Autowired ReservationExpirationAdminActionLogRepository repository;
    @Autowired EntityManager entityManager;

    @Test
    void adminFilters_shouldApplyIdentifiersTypesOutcomeActorAndBothTimeBounds() {
        UUID selectedOrder = UUID.randomUUID();
        UUID selectedWork = UUID.randomUUID();
        repository.saveAndFlush(ReservationExpirationAdminActionLog.recovery(
                selectedOrder, selectedWork, ReservationExpirationAdminActionOutcome.REQUEUED,
                "Mixed.Admin@example.com", FIRST_AT));
        repository.saveAndFlush(ReservationExpirationAdminActionLog.adoption(
                UUID.randomUUID(), UUID.randomUUID(), "other@example.com", SECOND_AT));
        entityManager.clear();

        var page = repository.findAll(ReservationExpirationAdminActionLogSpecifications.adminFilters(
                selectedOrder, selectedWork, ReservationExpirationAdminActionType.RECOVERY,
                ReservationExpirationAdminActionOutcome.REQUEUED, "  MIXED.ADMIN  ", FIRST_AT, FIRST_AT),
                PageRequest.of(0, 10));

        assertThat(page.getContent()).singleElement().satisfies(log -> {
            assertThat(log.getOrderId()).isEqualTo(selectedOrder);
            assertThat(log.getWorkId()).isEqualTo(selectedWork);
            assertThat(log.getActorEmail()).isEqualTo("Mixed.Admin@example.com");
        });
    }

    @Test
    void adminFilters_shouldTreatNullAndBlankOptionalFiltersAsAbsent() {
        UUID orderId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        repository.saveAndFlush(ReservationExpirationAdminActionLog.adoption(
                orderId, workId, "selected@example.com", FIRST_AT));
        entityManager.clear();

        var noFilters = repository.findAll(ReservationExpirationAdminActionLogSpecifications.adminFilters(
                null, null, null, null, null, null, null),
                PageRequest.of(0, 100, Sort.by("id")));
        var blankActor = repository.findAll(ReservationExpirationAdminActionLogSpecifications.adminFilters(
                orderId, null, null, null, "   ", null, null), PageRequest.of(0, 10));

        assertThat(noFilters.getContent()).extracting(ReservationExpirationAdminActionLog::getWorkId)
                .contains(workId);
        assertThat(blankActor.getContent()).extracting(ReservationExpirationAdminActionLog::getWorkId)
                .containsExactly(workId);
    }

    @Test
    void adminFilters_shouldApplyCreatedFromAndCreatedToIndependently() {
        UUID orderId = UUID.randomUUID();
        UUID firstWork = UUID.randomUUID();
        UUID secondWork = UUID.randomUUID();
        repository.saveAndFlush(ReservationExpirationAdminActionLog.recovery(
                orderId, firstWork, ReservationExpirationAdminActionOutcome.REQUEUED,
                "first@example.com", FIRST_AT));
        repository.saveAndFlush(ReservationExpirationAdminActionLog.recovery(
                orderId, secondWork, ReservationExpirationAdminActionOutcome.TERMINAL_NOOP,
                "second@example.com", SECOND_AT));
        entityManager.clear();

        var from = repository.findAll(ReservationExpirationAdminActionLogSpecifications.adminFilters(
                orderId, null, null, null, null, SECOND_AT, null), PageRequest.of(0, 10));
        var to = repository.findAll(ReservationExpirationAdminActionLogSpecifications.adminFilters(
                orderId, null, null, null, null, null, FIRST_AT), PageRequest.of(0, 10));

        assertThat(from.getContent()).extracting(ReservationExpirationAdminActionLog::getWorkId)
                .containsExactly(secondWork);
        assertThat(to.getContent()).extracting(ReservationExpirationAdminActionLog::getWorkId)
                .containsExactly(firstWork);
    }
}
