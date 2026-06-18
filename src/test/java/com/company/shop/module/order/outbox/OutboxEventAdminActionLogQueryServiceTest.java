package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.company.shop.module.order.outbox.dto.OutboxEventAdminActionLogResponseDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;

@ExtendWith(MockitoExtension.class)
class OutboxEventAdminActionLogQueryServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventAdminActionLogRepository outboxEventAdminActionLogRepository;

    @Mock
    private OutboxEventAdminActionLogMapper outboxEventAdminActionLogMapper;

    @Mock
    private OutboxEventProcessor outboxEventProcessor;

    @Test
    void getOutboxEventActionLogs_shouldReturnMappedPagedActionLogsForExistingOutboxEvent() {
        UUID outboxEventId = UUID.randomUUID();
        OutboxEventAdminActionLog log = OutboxEventAdminActionLog.requeue(outboxEventId, "admin@example.com");
        OutboxEventAdminActionLogResponseDTO response = response(outboxEventId);
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(outboxEventRepository.existsById(outboxEventId)).thenReturn(true);
        when(outboxEventAdminActionLogRepository.findByOutboxEventId(outboxEventId, pageable))
                .thenReturn(new PageImpl<>(List.of(log), pageable, 1));
        when(outboxEventAdminActionLogMapper.toDto(log)).thenReturn(response);

        Page<OutboxEventAdminActionLogResponseDTO> result = service().getOutboxEventActionLogs(outboxEventId, pageable);

        assertThat(result.getContent()).containsExactly(response);
        verify(outboxEventRepository).existsById(outboxEventId);
        verify(outboxEventAdminActionLogRepository).findByOutboxEventId(outboxEventId, pageable);
        verify(outboxEventAdminActionLogMapper).toDto(log);
        verifyNoInteractions(outboxEventProcessor);
    }

    @Test
    void getOutboxEventActionLogs_shouldThrowWhenOutboxEventDoesNotExist() {
        UUID outboxEventId = UUID.randomUUID();
        when(outboxEventRepository.existsById(outboxEventId)).thenReturn(false);

        assertThatThrownBy(() -> service().getOutboxEventActionLogs(outboxEventId, PageRequest.of(0, 20)))
                .isInstanceOf(OutboxEventNotFoundException.class)
                .hasMessage("Outbox event not found: " + outboxEventId)
                .extracting("errorCode")
                .isEqualTo("OUTBOX_EVENT_NOT_FOUND");

        verify(outboxEventRepository).existsById(outboxEventId);
        verifyNoMoreInteractions(outboxEventRepository);
        verifyNoInteractions(outboxEventAdminActionLogRepository, outboxEventAdminActionLogMapper, outboxEventProcessor);
    }

    @Test
    void getOutboxEventActionLogs_shouldApplyDefaultSortWhenPageableIsUnsorted() {
        UUID outboxEventId = UUID.randomUUID();
        Pageable requestedPageable = PageRequest.of(2, 5);
        Pageable expectedPageable = PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "createdAt"));
        when(outboxEventRepository.existsById(outboxEventId)).thenReturn(true);
        when(outboxEventAdminActionLogRepository.findByOutboxEventId(outboxEventId, expectedPageable))
                .thenReturn(Page.empty(expectedPageable));

        Page<OutboxEventAdminActionLogResponseDTO> result = service().getOutboxEventActionLogs(outboxEventId, requestedPageable);

        assertThat(result.getSort()).containsExactly(Sort.Order.desc("createdAt"));
        verify(outboxEventAdminActionLogRepository).findByOutboxEventId(outboxEventId, expectedPageable);
        verifyNoInteractions(outboxEventAdminActionLogMapper, outboxEventProcessor);
    }

    @Test
    void getOutboxEventActionLogs_shouldPreserveExplicitPageableSort() {
        UUID outboxEventId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(1, 10, Sort.by(Sort.Direction.ASC, "actorEmail"));
        when(outboxEventRepository.existsById(outboxEventId)).thenReturn(true);
        when(outboxEventAdminActionLogRepository.findByOutboxEventId(outboxEventId, pageable)).thenReturn(Page.empty(pageable));

        Page<OutboxEventAdminActionLogResponseDTO> result = service().getOutboxEventActionLogs(outboxEventId, pageable);

        assertThat(result.getSort()).containsExactly(Sort.Order.asc("actorEmail"));
        verify(outboxEventAdminActionLogRepository).findByOutboxEventId(outboxEventId, pageable);
        verifyNoInteractions(outboxEventAdminActionLogMapper, outboxEventProcessor);
    }

    private OutboxEventAdminActionLogQueryService service() {
        return new OutboxEventAdminActionLogQueryService(
                outboxEventRepository,
                outboxEventAdminActionLogRepository,
                outboxEventAdminActionLogMapper);
    }

    private OutboxEventAdminActionLogResponseDTO response(UUID outboxEventId) {
        return new OutboxEventAdminActionLogResponseDTO(
                UUID.randomUUID(),
                outboxEventId,
                OutboxEventAdminActionType.REQUEUE,
                "admin@example.com",
                Instant.parse("2026-01-01T10:00:00Z"),
                null);
    }
}
