package com.company.shop.module.order.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.company.shop.module.order.dto.StripePaymentConflictResponseDTO;
import com.company.shop.module.order.entity.StripePaymentConflict;
import com.company.shop.module.order.exception.StripePaymentConflictNotFoundException;
import com.company.shop.module.order.exception.StripePaymentConflictSortInvalidException;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;

@Service
public class StripePaymentConflictQueryService {
    private static final Set<String> ALLOWED_SORTS = Set.of("id", "observedAt", "orderId", "paymentId", "reason");
    private final StripePaymentConflictRepository repository;
    public StripePaymentConflictQueryService(StripePaymentConflictRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public Page<StripePaymentConflictResponseDTO> findAll(Pageable pageable) {
        return repository.findAll(deterministic(pageable)).map(this::toDto);
    }
    @Transactional(readOnly = true)
    public StripePaymentConflictResponseDTO findById(UUID id) {
        return repository.findById(id).map(this::toDto)
                .orElseThrow(() -> new StripePaymentConflictNotFoundException(id));
    }
    private Pageable deterministic(Pageable pageable) {
        Sort sort = pageable.getSort().isUnsorted()
                ? Sort.by(Sort.Order.desc("observedAt"), Sort.Order.asc("id"))
                : pageable.getSort();
        for (Sort.Order order : sort) if (!ALLOWED_SORTS.contains(order.getProperty()))
            throw new StripePaymentConflictSortInvalidException(order.getProperty());
        if (sort.getOrderFor("id") == null) sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
    private StripePaymentConflictResponseDTO toDto(StripePaymentConflict c) {
        return new StripePaymentConflictResponseDTO(c.getId(), c.getOrderId(), c.getPaymentId(), c.getStripeEventId(),
                c.getProviderPaymentId(), c.getEventType(), c.getOrderStatus(), c.getPaymentStatus(), c.getReason(),
                c.getObservedAt());
    }
}
