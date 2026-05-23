package com.company.shop.module.category.exception;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

/**
 * Exception thrown when an attempt is made to create or update a category with a name 
 * that already exists in the system.
 * <p>
 * Ensures unique naming constraints are respected at the business level before reaching 
 * the persistence layer.
 * </p>
 *
 * @since 1.0.0
 */
public class CategoryAlreadyExistsException extends BusinessException {

    /**
     * Constructs a new exception with the conflicting category name.
     *
     * @param name the name that caused the conflict.
     */
    public CategoryAlreadyExistsException(String name) {
        super(HttpStatus.CONFLICT, "CATEGORY_ALREADY_EXISTS", "error.business.category.alreadyExists", new Object[] {name}, "Category already exists with name: " + name);
    }
}