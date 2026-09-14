package com.company.shop.module.category.api.internal;

import java.util.Optional;
import java.util.UUID;

import com.company.shop.module.category.entity.Category;

/**
 * Category-owned boundary for resolving an active category used by a product.
 *
 * <p>The entity is intentionally returned because {@code Product} persists the existing
 * category relationship. Repository ownership nevertheless remains inside this module.</p>
 */
public interface ProductCategoryFacade {

    /**
     * Resolves and locks an active category for the duration of the caller's transaction.
     * Category retirement uses the same row lock as its assignment serialization boundary.
     */
    Optional<Category> findAssignableCategory(UUID categoryId);
}
