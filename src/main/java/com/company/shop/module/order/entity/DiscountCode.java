/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.SQLRestriction;

import com.company.shop.common.model.SoftDeleteEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Entity representing a promotional discount code within the system.
 * <p>
 * This class manages the lifecycle and validation of promotional codes, 
 * including expiration dates, usage limits, and active status.
 * It extends {@link SoftDeleteEntity} to support non-destructive deletion.
 * </p>
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "discount_codes")
@SQLRestriction("deleted = false")
public class DiscountCode extends SoftDeleteEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "discount_percent", nullable = false)
    private int discountPercent;

    @Column(name = "valid_from", nullable = false)
    private LocalDateTime validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDateTime validTo;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Default constructor required by JPA.
     */
    protected DiscountCode() {
    }

    /**
     * Checks if the current date is outside the validity range of the discount code.
     *
     * @param evaluationTime repository-owned wall-clock time used for this decision.
     * @return {@code true} if the code is either not yet valid or has already expired.
     */
    public boolean isExpired(LocalDateTime evaluationTime) {
        return evaluationTime.isBefore(validFrom) || evaluationTime.isAfter(validTo);
    }

    /**
     * Validates if the code is eligible for use based on status, dates, and limits.
     *
     * @param evaluationTime repository-owned wall-clock time used for this decision.
     * @return {@code true} if the code is active, not expired, and has not reached its usage limit.
     */
    public boolean canBeUsed(LocalDateTime evaluationTime) {
        return active && !isExpired(evaluationTime) && (usageLimit == null || usedCount < usageLimit);
    }

    /**
     * Increments the usage counter for this discount code.
     * This should be called after a successful order placement.
     */
    public void incrementUsage() {
        this.usedCount++;
    }

    public String getCode() {
        return code;
    }

    public int getDiscountPercent() {
        return discountPercent;
    }
}
