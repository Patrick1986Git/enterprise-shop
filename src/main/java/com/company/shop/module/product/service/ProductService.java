/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.product.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.company.shop.module.product.dto.ProductCreateDTO;
import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.dto.ProductSearchCriteria;
import com.company.shop.module.product.dto.ProductUpdateDTO;

/**
 * Service interface for managing product-related business operations.
 * <p>
 * This interface defines the contract for product catalog management, including
 * standard CRUD operations and advanced search capabilities using dynamic filtering.
 * </p>
 *
 * @since 1.0.0
 */
public interface ProductService {

    /**
     * Retrieves a paginated list of all products.
     *
     * @param pageable pagination and sorting information.
     * @return a page of product response objects.
     */
    Page<ProductResponseDTO> findAll(Pageable pageable);

    /**
     * Retrieves a paginated list of products belonging to a specific category.
     *
     * @param categoryId unique identifier of the category.
     * @param pageable   pagination and sorting information.
     * @return a page of products in the specified category.
     */
    Page<ProductResponseDTO> findAllByCategory(UUID categoryId, Pageable pageable);

    /**
     * Finds a single product by its unique identifier.
     *
     * @param id unique identifier of the product.
     * @return the product response object.
     * @throws com.company.shop.module.product.exception.ProductNotFoundException if no product is found.
     */
    ProductResponseDTO findById(UUID id);

    /**
     * Finds a single product by its SEO-friendly slug.
     *
     * @param slug the unique string identifier used in URLs.
     * @return the product response object.
     * @throws com.company.shop.module.product.exception.ProductNotFoundException if no product is found.
     */
    ProductResponseDTO findBySlug(String slug);

    /**
     * Creates and persists a new product.
     *
     * @param dto the data transfer object containing product details.
     * @return the newly created product response object.
     */
    ProductResponseDTO create(ProductCreateDTO dto);

    /**
     * Updates an existing product's information.
     *
     * @param id  unique identifier of the product to be updated.
     * @param dto the new product data.
     * @return the updated product response object.
     * @throws com.company.shop.module.product.exception.ProductNotFoundException if the product does not exist.
     */
    ProductResponseDTO update(UUID id, ProductUpdateDTO dto);

    /**
     * Removes a product from the system.
     * <p>
     * Implementation may use soft-delete mechanisms depending on the entity configuration.
     * </p>
     *
     * @param id unique identifier of the product to be deleted.
     */
    void delete(UUID id);

    /**
     * Performs an advanced search for products based on dynamic criteria.
     * <p>
     * This method supports full-text search, category filtering, and range-based filters.
     * Results are paginated and sorted according to the provided {@link Pageable} object.
     * </p>
     *
     * @param criteria the search parameters including query, price range, and rating.
     * @param pageable pagination and sorting information.
     * @return a page of products matching the criteria.
     */
    Page<ProductResponseDTO> searchProducts(ProductSearchCriteria criteria, Pageable pageable);
}
