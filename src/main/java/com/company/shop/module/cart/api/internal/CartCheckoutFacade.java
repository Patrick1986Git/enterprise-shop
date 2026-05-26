package com.company.shop.module.cart.api.internal;

import java.util.UUID;

public interface CartCheckoutFacade {

    CartCheckoutSnapshot getCartForCheckout(UUID userId);
}
