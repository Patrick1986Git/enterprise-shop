package com.company.shop.module.order.outbox;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventRequeueNotAllowedException;

@Service
public class OutboxEventAdminCommandService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventMapper outboxEventMapper;

    public OutboxEventAdminCommandService(OutboxEventRepository outboxEventRepository, OutboxEventMapper outboxEventMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventMapper = outboxEventMapper;
    }

    @Transactional
    public OutboxEventResponseDTO requeueFailedEvent(UUID id) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new OutboxEventNotFoundException(id));

        if (event.getStatus() != OutboxEventStatus.FAILED) {
            throw new OutboxEventRequeueNotAllowedException();
        }

        event.requeueForProcessing();
        return outboxEventMapper.toDto(event);
    }
}
