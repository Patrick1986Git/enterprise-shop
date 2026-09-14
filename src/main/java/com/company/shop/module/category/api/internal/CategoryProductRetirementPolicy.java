package com.company.shop.module.category.api.internal;

import java.util.UUID;

/**
 * Category-owned policy port used to prevent retirement while active products depend on a category.
 */
public interface CategoryProductRetirementPolicy {

    boolean hasActiveProducts(UUID categoryId);
}
