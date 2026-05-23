package com.company.shop.module.category.exception;

import org.springframework.http.HttpStatus;
import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when a generated or provided slug conflicts with an existing category.
 * <p>
 * This typically occurs when two different category names normalize to the same URL-friendly string.
 * </p>
 *
 * @since 1.0.0
 */
public class CategorySlugAlreadyExistsException extends BusinessException {

    /**
     * Constructs the exception with the conflicting slug.
     *
     * @param slug the URL-friendly string that is already taken.
     */
    public CategorySlugAlreadyExistsException(String slug) {
        super(HttpStatus.CONFLICT, "CATEGORY_SLUG_ALREADY_EXISTS", "error.business.category.slugAlreadyExists", new Object[] {slug}, "Category already exists with slug: " + slug);
    }
}