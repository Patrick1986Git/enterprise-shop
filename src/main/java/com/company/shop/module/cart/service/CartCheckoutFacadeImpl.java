package com.company.shop.module.cart.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.cart.api.internal.CartCheckoutItem;
import com.company.shop.module.cart.api.internal.CartCheckoutSnapshot;
import com.company.shop.module.cart.entity.Cart;

@Service
public class CartCheckoutFacadeImpl implements CartCheckoutFacade {

    private final CartService cartService;

    public CartCheckoutFacadeImpl(CartService cartService) {
        this.cartService = cartService;
    }

    @Override
    public CartCheckoutSnapshot getCartForCheckout(UUID userId) {
        Cart cart = cartService.getCartEntityForUser(userId);

        List<CartCheckoutItem> items = cart.getItems().stream()
                .map(item -> new CartCheckoutItem(item.getProduct().getId(), item.getQuantity()))
                .toList();

        return new CartCheckoutSnapshot(cart.getId(), items);
    }
}
