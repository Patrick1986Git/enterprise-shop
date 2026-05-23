package com.company.shop.module.category.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when a requested category cannot be found by its identifier or slug.
 *
 * @since 1.0.0
 */
public class CategoryNotFoundException extends BusinessException {

    /**
     * Constructs the exception using a unique identifier.
     *
     * @param categoryId the UUID of the missing category.
     */
    public CategoryNotFoundException(UUID categoryId) {
        super(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "error.business.category.notFound", new Object[] {categoryId}, "Category not found: " + categoryId);
    }

    /**
     * Constructs the exception using a human-readable slug.
     *
     * @param slug the URL-friendly identifier of the missing category.
     */
    public CategoryNotFoundException(String slug) {
        super(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "error.business.category.notFoundBySlug", new Object[] {slug}, "Category not found for slug: " + slug);
    }
}