package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.outbox.dto.OutboxEventAdminActionLogResponseDTO;
import com.company.shop.module.order.outbox.exception.OutboxEventActionLogDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;

@Service
public class OutboxEventAdminActionLogQueryService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventAdminActionLogRepository outboxEventAdminActionLogRepository;
    private final OutboxEventAdminActionLogMapper outboxEventAdminActionLogMapper;

    public OutboxEventAdminActionLogQueryService(
            OutboxEventRepository outboxEventRepository,
            OutboxEventAdminActionLogRepository outboxEventAdminActionLogRepository,
            OutboxEventAdminActionLogMapper outboxEventAdminActionLogMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventAdminActionLogRepository = outboxEventAdminActionLogRepository;
        this.outboxEventAdminActionLogMapper = outboxEventAdminActionLogMapper;
    }

    @Transactional(readOnly = true)
    public Page<OutboxEventAdminActionLogResponseDTO> getOutboxEventActionLogs(UUID outboxEventId, Pageable pageable) {
        if (!outboxEventRepository.existsById(outboxEventId)) {
            throw new OutboxEventNotFoundException(outboxEventId);
        }

        return outboxEventAdminActionLogRepository.findByOutboxEventId(outboxEventId, withDefaultSort(pageable))
                .map(outboxEventAdminActionLogMapper::toDto);
    }

    @Transactional(readOnly = true)
    public Page<OutboxEventAdminActionLogResponseDTO> searchActionLogs(
            UUID outboxEventId,
            OutboxEventAdminActionType actionType,
            String actorEmail,
            Instant createdFrom,
            Instant createdTo,
            Pageable pageable) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new OutboxEventActionLogDateRangeInvalidException();
        }

        return outboxEventAdminActionLogRepository.findAll(
                        OutboxEventAdminActionLogSpecifications.adminFilters(
                                outboxEventId, actionType, actorEmail, createdFrom, createdTo),
                        withDefaultSort(pageable))
                .map(outboxEventAdminActionLogMapper::toDto);
    }

    private Pageable withDefaultSort(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        if (pageable.isUnpaged()) {
            return Pageable.unpaged(DEFAULT_SORT);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
    }
}
