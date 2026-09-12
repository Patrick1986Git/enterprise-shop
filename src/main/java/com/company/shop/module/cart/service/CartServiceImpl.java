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
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.api.internal.CurrentUserAssociationFacade;
import com.company.shop.module.user.api.internal.CurrentUserFacade;

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
    private final ProductCatalogFacade productCatalogFacade;
    private final CurrentUserFacade currentUserFacade;
    private final CurrentUserAssociationFacade currentUserAssociationFacade;
    private final CartMapper cartMapper;

    public CartServiceImpl(CartRepository cartRepository,
                           ProductCatalogFacade productCatalogFacade,
                           CurrentUserFacade currentUserFacade,
                           CurrentUserAssociationFacade currentUserAssociationFacade,
                           CartMapper cartMapper) {
        this.cartRepository = cartRepository;
        this.productCatalogFacade = productCatalogFacade;
        this.currentUserFacade = currentUserFacade;
        this.currentUserAssociationFacade = currentUserAssociationFacade;
        this.cartMapper = cartMapper;
    }

    @Override
    public CartResponseDTO getMyCart() {
        UUID userId = currentUserFacade.getCurrentUser().id();
        Cart cart = getOrCreateCart(userId);
        return cartMapper.toDTO(cart);
    }

    /**
     * Adds product to cart with strict stock validation.
     */
    @Override
    public CartResponseDTO addToCart(AddToCartRequestDTO request) {

        UUID userId = currentUserFacade.getCurrentUser().id();
        Cart cart = getOrCreateCartForUpdate(userId);

        Product product = productCatalogFacade.resolveProductForCart(request.productId());

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

        UUID userId = currentUserFacade.getCurrentUser().id();
        Cart cart = getOrCreateCartForUpdate(userId);

        Product product = productCatalogFacade.resolveProductForCart(productId);

        if (product.getStock() < request.quantity()) {
            throw new InsufficientStockException(product.getStock());
        }

        cart.updateItemQuantity(productId, request.quantity());

        return cartMapper.toDTO(cartRepository.save(cart));
    }

    @Override
    public CartResponseDTO removeItem(UUID productId) {

        UUID userId = currentUserFacade.getCurrentUser().id();
        Cart cart = getOrCreateCartForUpdate(userId);

        cart.removeItem(productId);

        return cartMapper.toDTO(cartRepository.save(cart));
    }

    @Override
    public void clearCart() {

        clearCartForUser(currentUserFacade.getCurrentUser().id());
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
    private Cart getOrCreateCart(UUID userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseGet(() -> createCartWhileHoldingFirstTouchLock(userId));
    }

    private Cart getOrCreateCartForUpdate(UUID userId) {
        return cartRepository.findByUserIdWithItemsForUpdate(userId)
                .orElseGet(() -> createCartWhileHoldingFirstTouchLock(userId));
    }

    private Cart createCartWhileHoldingFirstTouchLock(UUID userId) {
        cartRepository.lockCartCreationForUser(userId);
        return cartRepository.findByUserIdWithItemsForUpdate(userId)
                .orElseGet(() -> {
                    User user = currentUserAssociationFacade.getCurrentUserForAssociation();
                    return cartRepository.save(new Cart(user));
                });
    }
}
