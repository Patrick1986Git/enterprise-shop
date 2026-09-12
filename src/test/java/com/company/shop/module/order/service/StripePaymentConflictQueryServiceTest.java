package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.company.shop.module.order.dto.StripePaymentConflictResponseDTO;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.entity.StripePaymentConflict;
import com.company.shop.module.order.entity.StripePaymentConflictReason;
import com.company.shop.module.order.exception.StripePaymentConflictNotFoundException;
import com.company.shop.module.order.exception.StripePaymentConflictSortInvalidException;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;

@ExtendWith(MockitoExtension.class)
class StripePaymentConflictQueryServiceTest {
    private static final UUID CONFLICT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-12T08:00:00Z");

    @Mock
    private StripePaymentConflictRepository repository;
    private StripePaymentConflictQueryService service;

    @BeforeEach
    void setUp() {
        service = new StripePaymentConflictQueryService(repository);
    }

    @Test
    void findAll_shouldApplyIndexedDefaultOrderPreservePageAndMapEvidence() {
        StripePaymentConflict conflict = conflict();
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(conflict)));

        var result = service.findAll(PageRequest.of(3, 17));

        Pageable applied = capturedPageable();
        assertThat(applied.getPageNumber()).isEqualTo(3);
        assertThat(applied.getPageSize()).isEqualTo(17);
        assertThat(applied.getSort().toList()).containsExactly(
                Sort.Order.desc("observedAt"), Sort.Order.asc("id"));
        assertMappedEvidence(result.getContent().getFirst());
    }

    @Test
    void findAll_shouldPreserveAllowedDirectionAndAppendAscendingIdTieBreak() {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.findAll(PageRequest.of(1, 9, Sort.by(Sort.Order.desc("reason"))));

        Pageable applied = capturedPageable();
        assertThat(applied.getSort().toList()).containsExactly(
                Sort.Order.desc("reason"), Sort.Order.asc("id"));
    }

    @Test
    void findAll_shouldPreserveExplicitIdWithoutDuplicatingIt() {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.findAll(PageRequest.of(0, 20, Sort.by(Sort.Order.asc("orderId"), Sort.Order.desc("id"))));

        Pageable applied = capturedPageable();
        assertThat(applied.getSort().toList()).containsExactly(
                Sort.Order.asc("orderId"), Sort.Order.desc("id"));
    }

    @Test
    void findAll_shouldRejectUnallowlistedPropertyBeforeRepositoryQuery() {
        Pageable unsafe = PageRequest.of(0, 20, Sort.by("order.user.email"));

        assertThatThrownBy(() -> service.findAll(unsafe))
                .isInstanceOf(StripePaymentConflictSortInvalidException.class)
                .hasMessageContaining("order.user.email")
                .satisfies(error -> assertThat(((StripePaymentConflictSortInvalidException) error).getErrorCode())
                        .isEqualTo("STRIPE_PAYMENT_CONFLICT_SORT_INVALID"));
        verify(repository, never()).findAll(any(Pageable.class));
    }

    @Test
    void findById_shouldMapAllOperationalEvidence() {
        when(repository.findById(CONFLICT_ID)).thenReturn(Optional.of(conflict()));

        assertMappedEvidence(service.findById(CONFLICT_ID));
    }

    @Test
    void findById_shouldThrowDomainNotFoundWhenMissing() {
        when(repository.findById(CONFLICT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(CONFLICT_ID))
                .isInstanceOf(StripePaymentConflictNotFoundException.class)
                .hasMessageContaining(CONFLICT_ID.toString())
                .satisfies(error -> assertThat(((StripePaymentConflictNotFoundException) error).getErrorCode())
                        .isEqualTo("STRIPE_PAYMENT_CONFLICT_NOT_FOUND"));
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(captor.capture());
        return captor.getValue();
    }

    private StripePaymentConflict conflict() {
        StripePaymentConflict conflict = new StripePaymentConflict(ORDER_ID, PAYMENT_ID, "evt_conflict",
                "pi_conflict", "payment_intent.succeeded", OrderStatus.CANCELLED, PaymentStatus.FAILED,
                StripePaymentConflictReason.TERMINAL_STATE_CONTRADICTION, OBSERVED_AT);
        ReflectionTestUtils.setField(conflict, "id", CONFLICT_ID);
        return conflict;
    }

    private void assertMappedEvidence(StripePaymentConflictResponseDTO dto) {
        assertThat(dto).isEqualTo(new StripePaymentConflictResponseDTO(CONFLICT_ID, ORDER_ID, PAYMENT_ID,
                "evt_conflict", "pi_conflict", "payment_intent.succeeded", OrderStatus.CANCELLED,
                PaymentStatus.FAILED, StripePaymentConflictReason.TERMINAL_STATE_CONTRADICTION, OBSERVED_AT));
    }
}
