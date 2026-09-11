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

    Optional<Category> findAssignableCategory(UUID categoryId);
}
