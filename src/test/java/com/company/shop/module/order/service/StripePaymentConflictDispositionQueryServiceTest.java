package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
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

import com.company.shop.module.order.entity.StripePaymentConflictDisposition;
import com.company.shop.module.order.entity.StripePaymentConflictDispositionType;
import com.company.shop.module.order.exception.StripePaymentConflictNotFoundException;
import com.company.shop.module.order.mapper.StripePaymentConflictDispositionMapper;
import com.company.shop.module.order.repository.StripePaymentConflictDispositionRepository;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;

@ExtendWith(MockitoExtension.class)
class StripePaymentConflictDispositionQueryServiceTest {
    private static final UUID CONFLICT_ID = UUID.randomUUID();
    @Mock StripePaymentConflictRepository conflictRepository;
    @Mock StripePaymentConflictDispositionRepository dispositionRepository;
    @Mock StripePaymentConflictDispositionMapper mapper;
    private StripePaymentConflictDispositionQueryService service;

    @BeforeEach void setUp() {
        service = new StripePaymentConflictDispositionQueryService(conflictRepository, dispositionRepository, mapper);
    }

    @Test
    void findByConflictId_shouldPreserveBoundsAndApplyDeterministicOrder() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(true);
        when(dispositionRepository.findByConflictId(eq(CONFLICT_ID), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(List.of(StripePaymentConflictDisposition.record(
                        CONFLICT_ID, StripePaymentConflictDispositionType.ACKNOWLEDGED, "admin@example.com"))));

        service.findByConflictId(CONFLICT_ID, PageRequest.of(2, 7, Sort.by("actorEmail")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(dispositionRepository).findByConflictId(eq(CONFLICT_ID), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(7);
        assertThat(pageable.getValue().getSort().toList()).containsExactly(
                Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    @Test
    void findByConflictId_shouldRejectMissingConflict() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(false);
        assertThatThrownBy(() -> service.findByConflictId(CONFLICT_ID, PageRequest.of(0, 20)))
                .isInstanceOf(StripePaymentConflictNotFoundException.class);
        verifyNoInteractions(dispositionRepository, mapper);
    }
}
