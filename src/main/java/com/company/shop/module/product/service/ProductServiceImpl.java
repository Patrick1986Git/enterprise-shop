/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.product.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.category.api.internal.ProductCategoryFacade;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.dto.ProductCreateDTO;
import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.dto.ProductSearchCriteria;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductCategoryNotFoundException;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.exception.ProductSkuAlreadyExistsException;
import com.company.shop.module.product.exception.ProductSlugAlreadyExistsException;
import com.company.shop.module.product.mapper.ProductMapper;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.product.specification.ProductSpecification;

@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    private static final Pattern WHITESPACE_OR_UNDERSCORE = Pattern.compile("[\\s_]+");
    private static final Pattern NON_ALPHANUMERIC_HYPHEN = Pattern.compile("[^a-z0-9-]");
    private static final Pattern MULTIPLE_HYPHENS = Pattern.compile("-{2,}");

    private static final String SKU_UNIQUE_CONSTRAINT = "uq_products_sku";
    private static final String SLUG_UNIQUE_CONSTRAINT = "uq_products_slug";
    private static final String SLUG_FALLBACK_PREFIX = "product";
    private static final int DETERMINISTIC_SUFFIX_PROBES = 5;
    private static final int RANDOM_SUFFIX_ATTEMPTS = 20;
    private static final int RANDOM_SUFFIX_LENGTH = 8;

    private final ProductRepository productRepo;
    private final ProductCategoryFacade productCategoryFacade;
    private final ProductMapper mapper;

    public ProductServiceImpl(ProductRepository productRepo,
            ProductCategoryFacade productCategoryFacade,
            ProductMapper mapper) {
        this.productRepo = productRepo;
        this.productCategoryFacade = productCategoryFacade;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> findAll(Pageable pageable) {
        return productRepo.findAll(pageable).map(mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> findAllByCategory(UUID categoryId, Pageable pageable) {
        return productRepo.findByCategoryId(categoryId, pageable).map(mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO findById(UUID id) {
        return mapper.toDto(getProductOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponseDTO findBySlug(String slug) {
        return productRepo.findBySlug(slug)
                .map(mapper::toDto)
                .orElseThrow(() -> new ProductNotFoundException(slug));
    }

    @Override
    public ProductResponseDTO create(ProductCreateDTO dto) {
        validateSkuUniquenessForCreate(dto.getSku());

        Category category = getCategoryOrThrow(dto.getCategoryId());
        String slug = buildUniqueSlug(dto.getName(), null);

        Product product = new Product(dto.getName(), slug, dto.getSku(), dto.getDescription(), dto.getPrice(), dto.getStock(),
                category);
        product.replaceImages(dto.getImageUrls());

        return saveAndMap(product, dto.getSku(), slug, null);
    }

    @Override
    public ProductResponseDTO update(UUID id, ProductCreateDTO dto) {
        Product product = getProductOrThrow(id);

        validateSkuUniquenessForUpdate(dto.getSku(), id);
        Category category = getCategoryOrThrow(dto.getCategoryId());
        String slug = buildUniqueSlug(dto.getName(), id);

        product.update(dto.getName(), slug, dto.getSku(), dto.getDescription(), dto.getPrice(), dto.getStock(), category);
        product.replaceImages(dto.getImageUrls());

        return saveAndMap(product, dto.getSku(), slug, id);
    }

    @Override
    public void delete(UUID id) {
        Product product = getProductOrThrow(id);
        product.delete();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponseDTO> searchProducts(ProductSearchCriteria criteria, Pageable pageable) {
        return productRepo.findAll(ProductSpecification.filterByCriteria(criteria), pageable)
                .map(mapper::toDto);
    }

    private Product getProductOrThrow(UUID id) {
        return productRepo.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    private Category getCategoryOrThrow(UUID categoryId) {
        return productCategoryFacade.findAssignableCategory(categoryId)
                .orElseThrow(() -> new ProductCategoryNotFoundException(categoryId));
    }

    private void validateSkuUniquenessForCreate(String sku) {
        if (productRepo.existsBySku(sku)) {
            throw new ProductSkuAlreadyExistsException(sku);
        }
    }

    private void validateSkuUniquenessForUpdate(String sku, UUID productId) {
        if (productRepo.existsBySkuAndIdNot(sku, productId)) {
            throw new ProductSkuAlreadyExistsException(sku);
        }
    }

    private String buildUniqueSlug(String name, UUID excludedProductId) {
        String baseSlug = generateSlug(name);

        if (!isSlugTaken(baseSlug, excludedProductId)) {
            return baseSlug;
        }

        for (int suffix = 2; suffix <= DETERMINISTIC_SUFFIX_PROBES + 1; suffix++) {
            String candidate = baseSlug + "-" + suffix;
            if (!isSlugTaken(candidate, excludedProductId)) {
                return candidate;
            }
        }

        for (int attempt = 0; attempt < RANDOM_SUFFIX_ATTEMPTS; attempt++) {
            String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_SUFFIX_LENGTH);
            String candidate = baseSlug + "-" + randomSuffix;
            if (!isSlugTaken(candidate, excludedProductId)) {
                return candidate;
            }
        }

        throw new ProductSlugAlreadyExistsException(baseSlug);
    }

    private boolean isSlugTaken(String slug, UUID excludedProductId) {
        if (excludedProductId == null) {
            return productRepo.existsBySlug(slug);
        }
        return productRepo.existsBySlugAndIdNot(slug, excludedProductId);
    }

    private ProductResponseDTO saveAndMap(Product product, String sku, String slug, UUID excludedProductId) {
        try {
            return mapper.toDto(productRepo.saveAndFlush(product));
        } catch (DataIntegrityViolationException ex) {
            String constraintName = extractConstraintName(ex);
            if (SKU_UNIQUE_CONSTRAINT.equals(constraintName)) {
                throw new ProductSkuAlreadyExistsException(sku);
            }
            if (SLUG_UNIQUE_CONSTRAINT.equals(constraintName)) {
                throw new ProductSlugAlreadyExistsException(slug);
            }

            if (excludedProductId == null && productRepo.existsBySku(sku)) {
                throw new ProductSkuAlreadyExistsException(sku);
            }
            if (excludedProductId != null && productRepo.existsBySkuAndIdNot(sku, excludedProductId)) {
                throw new ProductSkuAlreadyExistsException(sku);
            }
            if (isSlugTaken(slug, excludedProductId)) {
                throw new ProductSlugAlreadyExistsException(slug);
            }

            throw ex;
        }
    }

    private String extractConstraintName(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof ConstraintViolationException constraintViolationException) {
                return constraintViolationException.getConstraintName();
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private String generateSlug(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);

        String slug = WHITESPACE_OR_UNDERSCORE.matcher(normalized).replaceAll("-");
        slug = NON_ALPHANUMERIC_HYPHEN.matcher(slug).replaceAll("");
        slug = MULTIPLE_HYPHENS.matcher(slug).replaceAll("-");
        slug = slug.replaceAll("(^-|-$)", "");

        if (!slug.isBlank()) {
            return slug;
        }

        String fallbackSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_SUFFIX_LENGTH);
        return SLUG_FALLBACK_PREFIX + "-" + fallbackSuffix;
    }
}
