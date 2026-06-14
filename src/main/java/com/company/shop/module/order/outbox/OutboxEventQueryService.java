package com.company.shop.module.order.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;

@Service
public class OutboxEventQueryService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxEventQueryService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(readOnly = true)
    public OutboxEventSummaryDTO getSummary() {
        return new OutboxEventSummaryDTO(
                outboxEventRepository.countByStatus(OutboxEventStatus.PENDING),
                outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED),
                outboxEventRepository.countByStatus(OutboxEventStatus.FAILED),
                outboxEventRepository.count(),
                outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING).orElse(null),
                outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED).orElse(null));
    }
}
