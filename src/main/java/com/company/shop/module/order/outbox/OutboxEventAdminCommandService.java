package com.company.shop.module.order.outbox;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventRequeueNotAllowedException;
import com.company.shop.security.CurrentUserProvider;

@Service
public class OutboxEventAdminCommandService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventMapper outboxEventMapper;
    private final CurrentUserProvider currentUserProvider;

    public OutboxEventAdminCommandService(
            OutboxEventRepository outboxEventRepository,
            OutboxEventMapper outboxEventMapper,
            CurrentUserProvider currentUserProvider) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventMapper = outboxEventMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public OutboxEventResponseDTO requeueFailedEvent(UUID id) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new OutboxEventNotFoundException(id));

        if (event.getStatus() != OutboxEventStatus.FAILED) {
            throw new OutboxEventRequeueNotAllowedException();
        }

        String currentAdminEmail = normalizeCurrentAdminEmail(currentUserProvider.getCurrentUserEmail());
        event.requeueForProcessing(currentAdminEmail);
        return outboxEventMapper.toDto(event);
    }

    private String normalizeCurrentAdminEmail(String currentAdminEmail) {
        String normalizedEmail = currentAdminEmail == null ? null : currentAdminEmail.trim();
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            throw new IllegalArgumentException("current admin email must not be blank");
        }
        return normalizedEmail;
    }
}
