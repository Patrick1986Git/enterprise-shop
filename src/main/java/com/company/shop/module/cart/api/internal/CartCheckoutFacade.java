package com.company.shop.module.cart.api.internal;

import java.util.List;
import java.util.UUID;

public interface CartCheckoutFacade {

    CartCheckoutSnapshot getCartForCheckout(UUID userId);

    void reconcileCartAfterSuccessfulPayment(UUID userId, List<CartCheckoutItem> checkedOutItems);
}
