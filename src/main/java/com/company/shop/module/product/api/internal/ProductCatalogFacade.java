package com.company.shop.module.product.api.internal;

import java.util.List;
import java.util.UUID;

import com.company.shop.module.product.entity.Product;

public interface ProductCatalogFacade {

    /**
     * Resolves the active, managed product needed by the existing cart-item relationship.
     * This is an ordinary lookup: it neither locks nor reserves inventory.
     */
    Product resolveProductForCart(UUID productId);

    CheckoutProduct reserveProductForCheckout(UUID productId, int quantity);

    void releaseReservedInventory(List<ReservedInventoryItem> items);
}
