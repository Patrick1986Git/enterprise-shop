package com.company.shop.module.category.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when a category hierarchy rule is violated.
 * <p>
 * This includes scenarios such as attempting to set a category as its own parent 
 * or creating a circular dependency in the category tree.
 * </p>
 *
 * @since 1.0.0
 */
public class CategoryHierarchyException extends BusinessException {

    /**
     * Constructs a new hierarchy exception with a specific business message and error code.
     *
     * @param message   description of the hierarchy violation.
     * @param errorCode unique business error code for client-side handling.
     */
    public CategoryHierarchyException(String message, String errorCode) {
        super(HttpStatus.CONFLICT, errorCode, "error.business.category.hierarchyInvalid", new Object[] { message }, message);
    }
}