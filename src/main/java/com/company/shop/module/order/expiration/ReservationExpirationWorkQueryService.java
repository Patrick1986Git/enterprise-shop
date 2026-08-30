package com.company.shop.module.order.expiration;

import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationExpirationWorkQueryService {
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of(
            "id", "orderId", "status", "dueAt", "nextAttemptAt", "claimUntil", "attempts", "completedAt",
            "failedAt", "recoveryCount", "lastRecoveredAt", "lastRecoveredBy");

    private final ReservationExpirationWorkRepository repository;
    private final ReservationExpirationWorkMapper mapper;

    public ReservationExpirationWorkQueryService(
            ReservationExpirationWorkRepository repository, ReservationExpirationWorkMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<ReservationExpirationWorkResponseDTO> findAll(
            ReservationExpirationWorkStatus status, UUID orderId, Pageable pageable) {
        return repository.findAdminWork(status, orderId, deterministic(pageable))
                .map(mapper::toDto);
    }

    @Transactional(readOnly = true)
    public ReservationExpirationWorkResponseDTO findById(UUID workId) {
        return repository.findById(workId)
                .map(mapper::toDto)
                .orElseThrow(() -> new ReservationExpirationWorkNotFoundException(workId));
    }

    private Pageable deterministic(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Direction.ASC, "dueAt", "id");
        } else {
            for (Sort.Order order : sort) {
                if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                    throw new ReservationExpirationWorkSortInvalidException(order.getProperty());
                }
            }
            if (sort.getOrderFor("id") == null) {
                sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
