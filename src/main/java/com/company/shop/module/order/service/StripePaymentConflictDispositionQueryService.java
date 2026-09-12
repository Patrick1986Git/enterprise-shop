package com.company.shop.module.order.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.dto.StripePaymentConflictDispositionResponseDTO;
import com.company.shop.module.order.exception.StripePaymentConflictNotFoundException;
import com.company.shop.module.order.mapper.StripePaymentConflictDispositionMapper;
import com.company.shop.module.order.repository.StripePaymentConflictDispositionRepository;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;

@Service
public class StripePaymentConflictDispositionQueryService {
    private static final Sort HISTORY_ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    private final StripePaymentConflictRepository conflictRepository;
    private final StripePaymentConflictDispositionRepository dispositionRepository;
    private final StripePaymentConflictDispositionMapper mapper;

    public StripePaymentConflictDispositionQueryService(
            StripePaymentConflictRepository conflictRepository,
            StripePaymentConflictDispositionRepository dispositionRepository,
            StripePaymentConflictDispositionMapper mapper) {
        this.conflictRepository = conflictRepository;
        this.dispositionRepository = dispositionRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public Page<StripePaymentConflictDispositionResponseDTO> findByConflictId(UUID conflictId, Pageable pageable) {
        if (!conflictRepository.existsById(conflictId)) {
            throw new StripePaymentConflictNotFoundException(conflictId);
        }
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), HISTORY_ORDER);
        return dispositionRepository.findByConflictId(conflictId, ordered).map(mapper::toDto);
    }
}
