package com.company.shop.module.order.outbox;

import java.time.Duration;
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
import com.company.shop.module.order.outbox.exception.OutboxEventAttemptsRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventLastAttemptDateRangeInvalidException;
import com.company.shop.module.order.outbox.exception.OutboxEventNotFoundException;
import com.company.shop.module.order.outbox.exception.OutboxEventProcessedDateRangeInvalidException;

@Service
public class OutboxEventQueryService {

    static final Duration STALE_THRESHOLD_DURATION = Duration.ofMinutes(15);
    static final int HIGH_FAILED_ATTEMPTS_THRESHOLD = 3;

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventMapper outboxEventMapper;

    public OutboxEventQueryService(OutboxEventRepository outboxEventRepository, OutboxEventMapper outboxEventMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventMapper = outboxEventMapper;
    }

    @Transactional(readOnly = true)
    public OutboxEventSummaryDTO getSummary() {
        Instant staleThreshold = Instant.now().minus(STALE_THRESHOLD_DURATION);

        return new OutboxEventSummaryDTO(
                outboxEventRepository.countByStatus(OutboxEventStatus.PENDING),
                outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSED),
                outboxEventRepository.countByStatus(OutboxEventStatus.FAILED),
                outboxEventRepository.count(),
                outboxEventRepository.countByRequeueCountGreaterThan(0),
                outboxEventRepository.sumRequeueCount(),
                outboxEventRepository.countByStatusAndCreatedAtLessThanEqual(OutboxEventStatus.PENDING, staleThreshold),
                outboxEventRepository.countByStatusAndLastAttemptAtLessThanEqual(OutboxEventStatus.FAILED, staleThreshold),
                outboxEventRepository.countByStatusAndAttemptsGreaterThanEqual(
                        OutboxEventStatus.FAILED, HIGH_FAILED_ATTEMPTS_THRESHOLD),
                STALE_THRESHOLD_DURATION.toMinutes(),
                HIGH_FAILED_ATTEMPTS_THRESHOLD,
                outboxEventRepository.findOldestCreatedAtByStatus(OutboxEventStatus.PENDING).orElse(null),
                outboxEventRepository.findNewestCreatedAtByStatus(OutboxEventStatus.FAILED).orElse(null),
                outboxEventRepository.findNewestAttemptAt().orElse(null),
                outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.PROCESSED).orElse(null),
                outboxEventRepository.findNewestAttemptAtByStatus(OutboxEventStatus.FAILED).orElse(null));
    }

    @Transactional(readOnly = true)
    public OutboxEventDetailResponseDTO getEvent(UUID id) {
        return outboxEventRepository.findById(id)
                .map(outboxEventMapper::toDetailDto)
                .orElseThrow(() -> new OutboxEventNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<OutboxEventResponseDTO> getEvents(OutboxEventAdminSearchCriteria criteria, Pageable pageable) {
        if (criteria.createdFrom() != null
                && criteria.createdTo() != null
                && criteria.createdFrom().isAfter(criteria.createdTo())) {
            throw new OutboxEventDateRangeInvalidException();
        }
        if (criteria.processedFrom() != null
                && criteria.processedTo() != null
                && criteria.processedFrom().isAfter(criteria.processedTo())) {
            throw new OutboxEventProcessedDateRangeInvalidException();
        }
        if (criteria.lastAttemptFrom() != null
                && criteria.lastAttemptTo() != null
                && criteria.lastAttemptFrom().isAfter(criteria.lastAttemptTo())) {
            throw new OutboxEventLastAttemptDateRangeInvalidException();
        }
        if ((criteria.attemptsMin() != null && criteria.attemptsMin() < 0)
                || (criteria.attemptsMax() != null && criteria.attemptsMax() < 0)
                || (criteria.attemptsMin() != null
                        && criteria.attemptsMax() != null
                        && criteria.attemptsMin() > criteria.attemptsMax())) {
            throw new OutboxEventAttemptsRangeInvalidException();
        }

        Specification<OutboxEvent> specification = criteria.problemType() == null
                ? OutboxEventSpecifications.adminFilters(criteria)
                : OutboxEventSpecifications.adminFilters(
                        criteria, Instant.now().minus(STALE_THRESHOLD_DURATION), HIGH_FAILED_ATTEMPTS_THRESHOLD);
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
