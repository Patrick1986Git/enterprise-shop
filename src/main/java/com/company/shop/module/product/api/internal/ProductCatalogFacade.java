package com.company.shop.module.product.api.internal;

import java.util.List;
import java.util.UUID;

public interface ProductCatalogFacade {

    CheckoutProduct reserveProductForCheckout(UUID productId, int quantity);

    void releaseReservedInventory(List<ReservedInventoryItem> items);
}
