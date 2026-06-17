package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventRequeueNotAllowedException;
import com.company.shop.security.CurrentUserProvider;

@ExtendWith(MockitoExtension.class)
class OutboxEventAdminCommandServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private OutboxEventProcessor outboxEventProcessor;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private OutboxEventAdminCommandService outboxEventAdminCommandService;

    @BeforeEach
    void setUp() {
        outboxEventAdminCommandService = new OutboxEventAdminCommandService(
                outboxEventRepository, outboxEventMapper, currentUserProvider);
    }

    @Test
    void requeueFailedEvent_shouldRequeueFailedEventAndReturnMappedDto() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        event.markFailed("boom");
        OutboxEventResponseDTO response = response(eventId, OutboxEventStatus.PENDING);
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(currentUserProvider.getCurrentUserEmail()).thenReturn(" admin@example.com ");
        when(outboxEventMapper.toDto(event)).thenReturn(response);

        OutboxEventResponseDTO result = outboxEventAdminCommandService.requeueFailedEvent(eventId);

        assertThat(result).isEqualTo(response);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getLastError()).isNull();
        assertThat(event.getProcessedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getRequeueCount()).isEqualTo(1);
        assertThat(event.getLastRequeuedAt()).isNotNull();
        assertThat(event.getLastRequeuedBy()).isEqualTo("admin@example.com");
        verify(outboxEventRepository).findById(eventId);
        verify(currentUserProvider).getCurrentUserEmail();
        verify(outboxEventMapper).toDto(event);
        verifyNoInteractions(outboxEventProcessor);
        verifyNoMoreInteractions(outboxEventRepository, currentUserProvider, outboxEventMapper);
    }

    @Test
    void requeueFailedEvent_shouldThrowWhenEventIsMissing() {
        UUID eventId = UUID.randomUUID();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> outboxEventAdminCommandService.requeueFailedEvent(eventId))
                .isInstanceOf(OutboxEventNotFoundException.class)
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_NOT_FOUND");

        verify(outboxEventRepository).findById(eventId);
        verifyNoInteractions(currentUserProvider, outboxEventMapper, outboxEventProcessor);
        verifyNoMoreInteractions(outboxEventRepository);
    }

    @Test
    void requeueFailedEvent_shouldThrowWhenEventIsPending() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> outboxEventAdminCommandService.requeueFailedEvent(eventId))
                .isInstanceOf(OutboxEventRequeueNotAllowedException.class)
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_REQUEUE_NOT_ALLOWED");

        verify(outboxEventRepository).findById(eventId);
        verifyNoInteractions(currentUserProvider, outboxEventMapper, outboxEventProcessor);
    }

    @Test
    void requeueFailedEvent_shouldThrowWhenEventIsProcessed() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending("Order", UUID.randomUUID(), "OrderPlaced", "{}");
        event.markProcessed();
        when(outboxEventRepository.findById(eventId)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> outboxEventAdminCommandService.requeueFailedEvent(eventId))
                .isInstanceOf(OutboxEventRequeueNotAllowedException.class)
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_REQUEUE_NOT_ALLOWED");

        verify(outboxEventRepository).findById(eventId);
        verifyNoInteractions(currentUserProvider, outboxEventMapper, outboxEventProcessor);
    }

    private OutboxEventResponseDTO response(UUID id, OutboxEventStatus status) {
        return new OutboxEventResponseDTO(
                id,
                "Order",
                UUID.randomUUID(),
                "OrderPlaced",
                status,
                Instant.parse("2026-01-01T10:00:00Z"),
                null,
                1,
                null,
                1,
                Instant.parse("2026-01-01T10:02:00Z"),
                "admin@example.com");
    }
}
