package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.order.entity.StripePaymentConflictDisposition;
import com.company.shop.module.order.entity.StripePaymentConflictDispositionType;
import com.company.shop.module.order.exception.StripePaymentConflictNotFoundException;
import com.company.shop.module.order.mapper.StripePaymentConflictDispositionMapper;
import com.company.shop.module.order.repository.StripePaymentConflictDispositionRepository;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;
import com.company.shop.security.CurrentUserProvider;

@ExtendWith(MockitoExtension.class)
class StripePaymentConflictDispositionCommandServiceTest {
    private static final UUID CONFLICT_ID = UUID.randomUUID();
    @Mock StripePaymentConflictRepository conflictRepository;
    @Mock StripePaymentConflictDispositionRepository dispositionRepository;
    @Mock StripePaymentConflictDispositionMapper mapper;
    @Mock CurrentUserProvider currentUserProvider;
    private StripePaymentConflictDispositionCommandService service;

    @BeforeEach
    void setUp() {
        service = new StripePaymentConflictDispositionCommandService(
                conflictRepository, dispositionRepository, mapper, currentUserProvider);
    }

    @Test
    void append_shouldPersistAcknowledgementWithServerDerivedActor() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(true);
        when(currentUserProvider.getCurrentUserEmail()).thenReturn(" admin@example.com ");
        when(dispositionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.append(CONFLICT_ID, StripePaymentConflictDispositionType.ACKNOWLEDGED);

        verify(dispositionRepository).save(any(StripePaymentConflictDisposition.class));
        verify(mapper).toDto(org.mockito.ArgumentMatchers.argThat(disposition ->
                disposition.getConflictId().equals(CONFLICT_ID)
                        && disposition.getActionType() == StripePaymentConflictDispositionType.ACKNOWLEDGED
                        && disposition.getActorEmail().equals("admin@example.com")));
    }

    @Test
    void append_shouldPersistEscalationAsIndependentAction() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(true);
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");
        when(dispositionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.append(CONFLICT_ID, StripePaymentConflictDispositionType.ESCALATED);

        verify(mapper).toDto(org.mockito.ArgumentMatchers.argThat(disposition ->
                disposition.getActionType() == StripePaymentConflictDispositionType.ESCALATED));
    }

    @Test
    void append_shouldPreserveRepeatedAcknowledgementsAsSeparateHistory() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(true);
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");
        when(dispositionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.append(CONFLICT_ID, StripePaymentConflictDispositionType.ACKNOWLEDGED);
        service.append(CONFLICT_ID, StripePaymentConflictDispositionType.ACKNOWLEDGED);

        verify(dispositionRepository, times(2)).save(any(StripePaymentConflictDisposition.class));
    }

    @Test
    void append_shouldRejectMissingConflictBeforeReadingActorOrWriting() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.append(CONFLICT_ID, StripePaymentConflictDispositionType.ACKNOWLEDGED))
                .isInstanceOf(StripePaymentConflictNotFoundException.class);
        verifyNoInteractions(currentUserProvider, dispositionRepository, mapper);
    }

    @Test
    void append_shouldRejectBlankAuthenticatedActor() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(true);
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("  ");

        assertThatThrownBy(() -> service.append(CONFLICT_ID, StripePaymentConflictDispositionType.ACKNOWLEDGED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("current admin email must not be blank");
        verify(dispositionRepository, never()).save(any());
    }

    @Test
    void append_shouldRejectMissingAuthenticatedActor() {
        when(conflictRepository.existsById(CONFLICT_ID)).thenReturn(true);
        when(currentUserProvider.getCurrentUserEmail()).thenReturn(null);

        assertThatThrownBy(() -> service.append(CONFLICT_ID, StripePaymentConflictDispositionType.ESCALATED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("current admin email must not be blank");
        verify(dispositionRepository, never()).save(any());
    }
}
