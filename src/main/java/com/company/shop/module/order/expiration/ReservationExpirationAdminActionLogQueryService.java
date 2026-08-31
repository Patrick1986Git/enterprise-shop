package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationExpirationAdminActionLogQueryService {
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("createdAt", "id");
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private final ReservationExpirationAdminActionLogRepository repository;
    private final ReservationExpirationAdminActionLogMapper mapper;

    public ReservationExpirationAdminActionLogQueryService(
            ReservationExpirationAdminActionLogRepository repository,
            ReservationExpirationAdminActionLogMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<ReservationExpirationAdminActionLogResponseDTO> search(
            UUID orderId, UUID workId, ReservationExpirationAdminActionType actionType,
            ReservationExpirationAdminActionOutcome outcome, String actorEmail,
            Instant createdFrom, Instant createdTo, Pageable pageable) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new ReservationExpirationActionLogDateRangeInvalidException();
        }
        return repository.findAll(ReservationExpirationAdminActionLogSpecifications.adminFilters(
                orderId, workId, actionType, outcome, actorEmail, createdFrom, createdTo), deterministic(pageable))
                .map(mapper::toDto);
    }

    private Pageable deterministic(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = DEFAULT_SORT;
        } else {
            for (Sort.Order order : sort) {
                if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                    throw new ReservationExpirationActionLogSortInvalidException(order.getProperty());
                }
            }
            if (sort.getOrderFor("id") == null) sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        return pageable.isUnpaged() ? Pageable.unpaged(sort)
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
