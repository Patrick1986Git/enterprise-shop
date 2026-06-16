package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.outbox.dto.OutboxEventDetailResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventResponseDTO;
import com.company.shop.module.order.outbox.dto.OutboxEventSummaryDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;

@Service
public class OutboxEventQueryService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventMapper outboxEventMapper;

    public OutboxEventQueryService(OutboxEventRepository outboxEventRepository, OutboxEventMapper outboxEventMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventMapper = outboxEventMapper;
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

    @Transactional(readOnly = true)
    public OutboxEventDetailResponseDTO getEvent(UUID id) {
        return outboxEventRepository.findById(id)
                .map(outboxEventMapper::toDetailDto)
                .orElseThrow(() -> new OutboxEventNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<OutboxEventResponseDTO> getEvents(
            OutboxEventStatus status,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Instant createdFrom,
            Instant createdTo,
            Pageable pageable) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new OutboxEventDateRangeInvalidException();
        }

        Specification<OutboxEvent> specification = OutboxEventSpecifications.adminFilters(
                status, aggregateType, aggregateId, eventType, createdFrom, createdTo);
        Pageable effectivePageable = withDefaultSort(pageable);

        return outboxEventRepository.findAll(specification, effectivePageable)
                .map(outboxEventMapper::toDto);
    }

    private Pageable withDefaultSort(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
