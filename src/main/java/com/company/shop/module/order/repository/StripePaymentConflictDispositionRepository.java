package com.company.shop.module.order.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.company.shop.module.order.entity.StripePaymentConflictDisposition;

public interface StripePaymentConflictDispositionRepository
        extends JpaRepository<StripePaymentConflictDisposition, UUID> {
    Page<StripePaymentConflictDisposition> findByConflictId(UUID conflictId, Pageable pageable);
}
