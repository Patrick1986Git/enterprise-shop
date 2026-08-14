/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 */

package com.company.shop.module.cart.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.cart.api.internal.CartCheckoutItem;
import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.dto.UpdateCartItemRequestDTO;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.entity.CartItem;
import com.company.shop.module.cart.exception.CartNotFoundException;
import com.company.shop.module.cart.exception.InsufficientStockException;
import com.company.shop.module.cart.mapper.CartMapper;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;

/**
 * Production implementation of {@link CartService}.
 *
 * <p>
 * Fully aligned with enterprise exception architecture.
 * Only BusinessException-based domain exceptions are thrown.
 * No JPA or generic runtime exceptions leak outside the module.
 * </p>
 *
 * @since 2.0.0
 */
@Service
@Transactional
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserService userService;
    private final CartMapper cartMapper;

    public CartServiceImpl(CartRepository cartRepository,
                           ProductRepository productRepository,
                           UserService userService,
                           CartMapper cartMapper) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userService = userService;
        this.cartMapper = cartMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponseDTO getMyCart() {
        User user = userService.getCurrentUserEntity();
        Cart cart = getOrCreateCart(user);
        return cartMapper.toDTO(cart);
    }

    /**
     * Adds product to cart with strict stock validation.
     */
    @Override
    public CartResponseDTO addToCart(AddToCartRequestDTO request) {

        User user = userService.getCurrentUserEntity();
        Cart cart = getOrCreateCartForUpdate(user);

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));

        int currentInCart = cart.getItems().stream()
                .filter(item -> item.getProduct().getId().equals(request.productId()))
                .mapToInt(CartItem::getQuantity)
                .sum();

        if (product.getStock() < (currentInCart + request.quantity())) {
            throw new InsufficientStockException(product.getStock());
        }

        cart.addItem(product, request.quantity());

        return cartMapper.toDTO(cartRepository.save(cart));
    }

    /**
     * Updates quantity of a specific cart item.
     */
    @Override
    public CartResponseDTO updateItemQuantity(UUID productId, UpdateCartItemRequestDTO request) {

        User user = userService.getCurrentUserEntity();
        Cart cart = getOrCreateCartForUpdate(user);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.getStock() < request.quantity()) {
            throw new InsufficientStockException(product.getStock());
        }

        cart.updateItemQuantity(productId, request.quantity());

        return cartMapper.toDTO(cartRepository.save(cart));
    }

    @Override
    public CartResponseDTO removeItem(UUID productId) {

        User user = userService.getCurrentUserEntity();
        Cart cart = getOrCreateCartForUpdate(user);

        cart.removeItem(productId);

        return cartMapper.toDTO(cartRepository.save(cart));
    }

    @Override
    public void clearCart() {

        User user = userService.getCurrentUserEntity();
        clearCartForUser(user.getId());
    }

    @Override
    public void clearCartForUser(UUID userId) {

        cartRepository.findByUserIdWithItemsForUpdate(userId)
                .ifPresent(cart -> {
                    cart.clear();
                    cartRepository.save(cart);
                });
    }

    @Override
    public void reconcileCartForUser(UUID userId, List<CartCheckoutItem> checkedOutItems) {
        cartRepository.findByUserIdWithItemsForUpdate(userId)
                .ifPresent(cart -> {
                    checkedOutItems.forEach(item -> cart.reconcileItem(item.productId(), item.quantity()));
                    cartRepository.save(cart);
                });
    }

    /**
     * Returns raw Cart entity for internal module processing.
     */
    @Override
    @Transactional(readOnly = true)
    public Cart getCartEntityForUser(UUID userId) {

        return cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> new CartNotFoundException(userId));
    }

    /**
     * Ensures a cart exists for the user.
     */
    private Cart getOrCreateCart(User user) {

        return cartRepository.findByUserIdWithItems(user.getId())
                .orElseGet(() -> cartRepository.save(new Cart(user)));
    }

    private Cart getOrCreateCartForUpdate(User user) {
        return cartRepository.findByUserIdWithItemsForUpdate(user.getId())
                .orElseGet(() -> cartRepository.save(new Cart(user)));
    }
}
