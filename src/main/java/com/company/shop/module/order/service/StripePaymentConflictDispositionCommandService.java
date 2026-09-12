package com.company.shop.module.order.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.dto.StripePaymentConflictDispositionResponseDTO;
import com.company.shop.module.order.entity.StripePaymentConflictDisposition;
import com.company.shop.module.order.entity.StripePaymentConflictDispositionType;
import com.company.shop.module.order.exception.StripePaymentConflictNotFoundException;
import com.company.shop.module.order.mapper.StripePaymentConflictDispositionMapper;
import com.company.shop.module.order.repository.StripePaymentConflictDispositionRepository;
import com.company.shop.module.order.repository.StripePaymentConflictRepository;
import com.company.shop.security.CurrentUserProvider;

@Service
public class StripePaymentConflictDispositionCommandService {
    private final StripePaymentConflictRepository conflictRepository;
    private final StripePaymentConflictDispositionRepository dispositionRepository;
    private final StripePaymentConflictDispositionMapper mapper;
    private final CurrentUserProvider currentUserProvider;

    public StripePaymentConflictDispositionCommandService(
            StripePaymentConflictRepository conflictRepository,
            StripePaymentConflictDispositionRepository dispositionRepository,
            StripePaymentConflictDispositionMapper mapper,
            CurrentUserProvider currentUserProvider) {
        this.conflictRepository = conflictRepository;
        this.dispositionRepository = dispositionRepository;
        this.mapper = mapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public StripePaymentConflictDispositionResponseDTO append(
            UUID conflictId, StripePaymentConflictDispositionType actionType) {
        if (!conflictRepository.existsById(conflictId)) {
            throw new StripePaymentConflictNotFoundException(conflictId);
        }
        String actorEmail = normalizeActor(currentUserProvider.getCurrentUserEmail());
        StripePaymentConflictDisposition disposition = dispositionRepository.save(
                StripePaymentConflictDisposition.record(conflictId, actionType, actorEmail));
        return mapper.toDto(disposition);
    }

    private String normalizeActor(String actorEmail) {
        String normalized = actorEmail == null ? null : actorEmail.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("current admin email must not be blank");
        }
        return normalized;
    }
}
