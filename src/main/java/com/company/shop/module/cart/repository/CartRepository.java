/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.cart.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.cart.entity.Cart;

import jakarta.persistence.LockModeType;

/**
 * Repository interface for {@link Cart} entity management.
 * <p>
 * Provides specialized methods for retrieving shopping carts with optimized 
 * fetching strategies to minimize database roundtrips.
 * </p>
 *
 * @since 1.0.0
 */
public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * Retrieves a user's cart along with all its items and associated product details.
     * <p>
     * <strong>Performance Optimization:</strong> Uses a JPQL {@code FETCH JOIN} to eagerly 
     * load the cart items and product entities in a single SQL query, effectively 
     * preventing the N+1 select problem.
     * </p>
     *
     * @param userId unique identifier of the user who owns the cart.
     * @return an {@link Optional} containing the fully initialized cart if found.
     */
    @Query("SELECT c FROM Cart c " +
           "LEFT JOIN FETCH c.items i " +
           "LEFT JOIN FETCH i.product p " +
           "WHERE c.user.id = :userId")
    Optional<Cart> findByUserIdWithItems(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT DISTINCT c FROM Cart c " +
           "LEFT JOIN FETCH c.items i " +
           "LEFT JOIN FETCH i.product p " +
           "WHERE c.user.id = :userId")
    Optional<Cart> findByUserIdWithItemsForUpdate(@Param("userId") UUID userId);

    /**
     * Finds a cart associated with a specific user.
     * <p>
     * Note: This method uses lazy loading for the items collection by default.
     * For full cart views, prefer {@link #findByUserIdWithItems(UUID)}.
     * </p>
     *
     * @param userId unique identifier of the cart owner.
     * @return an {@link Optional} containing the cart if it exists.
     */
    Optional<Cart> findByUserId(UUID userId);
}
