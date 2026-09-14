package com.company.shop.module.category.exception;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.company.shop.common.exception.BusinessException;

public class CategoryRetirementConflictException extends BusinessException {

    private CategoryRetirementConflictException(UUID categoryId, String dependency) {
        super(HttpStatus.CONFLICT, "CATEGORY_RETIREMENT_BLOCKED",
                "error.business.category.retirementBlocked", new Object[] { categoryId, dependency },
                "Category cannot be retired while active " + dependency + " depend on it: " + categoryId);
    }

    public static CategoryRetirementConflictException activeProducts(UUID categoryId) {
        return new CategoryRetirementConflictException(categoryId, "products");
    }

    public static CategoryRetirementConflictException activeChildren(UUID categoryId) {
        return new CategoryRetirementConflictException(categoryId, "child categories");
    }
}
