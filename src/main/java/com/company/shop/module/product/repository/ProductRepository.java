/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.product.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.company.shop.module.product.entity.Product;

import jakarta.persistence.LockModeType;

/**
 * Data access layer for {@link Product} entity management.
 * <p>
 * Supports advanced querying through {@link JpaSpecificationExecutor} and 
 * provides transactional locking mechanisms to ensure data consistency during 
 * high-concurrency inventory operations.
 * </p>
 *
 * @since 1.0.0
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    boolean existsByCategoryId(UUID categoryId);

    /**
     * Retrieves a product by its unique identifier with a pessimistic write lock.
     * <p>
     * Use this method during checkout or stock updates to prevent concurrent 
     * modifications and ensure ACID compliance for inventory levels.
     * </p>
     *
     * @param id the unique identifier of the product.
     * @return an {@link Optional} containing the locked product, or empty if not found.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") UUID id);

    /**
     * Finds a product by its SEO-friendly slug.
     *
     * @param slug the unique product slug.
     * @return an {@link Optional} containing the product.
     */
    Optional<Product> findBySlug(String slug);

    /**
     * Checks if a product exists with the given Stock Keeping Unit (SKU).
     *
     * @param sku the SKU to verify.
     * @return {@code true} if a record exists.
     */
    boolean existsBySku(String sku);

    /**
     * Checks if a product exists with the given slug.
     *
     * @param slug the slug to verify.
     * @return {@code true} if a record exists.
     */
    boolean existsBySlug(String slug);

    /**
     * Checks if another product exists with the given SKU (excluding current entity).
     */
    boolean existsBySkuAndIdNot(String sku, UUID id);

    /**
     * Checks if another product exists with the given slug (excluding current entity).
     */
    boolean existsBySlugAndIdNot(String slug, UUID id);

    /**
     * Retrieves a paginated list of products belonging to a specific category.
     *
     * @param categoryId the identifier of the category.
     * @param pageable   pagination and sorting configuration.
     * @return a page of products.
     */
    Page<Product> findByCategoryId(UUID categoryId, Pageable pageable);
}
