package com.company.shop.module.cart.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.dto.UpdateCartItemRequestDTO;
import com.company.shop.module.cart.service.CartService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/me/cart")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Cart", description = "Operations on the authenticated user's cart.")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "Get the authenticated user's cart")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cart returned successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    public ResponseEntity<CartResponseDTO> getCart() {
        return ResponseEntity.ok(cartService.getMyCart());
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product to the cart")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product added to cart."),
            @ApiResponse(responseCode = "400", description = "Invalid request payload."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    public ResponseEntity<CartResponseDTO> addCartItem(@Valid @RequestBody AddToCartRequestDTO request) {
        return ResponseEntity.ok(cartService.addToCart(request));
    }

    @PatchMapping("/items/{productId}")
    @Operation(summary = "Update cart item quantity")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cart item quantity updated."),
            @ApiResponse(responseCode = "400", description = "Invalid request payload."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    public ResponseEntity<CartResponseDTO> updateCartItemQuantity(
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateCartItemRequestDTO request) {
        return ResponseEntity.ok(cartService.updateItemQuantity(productId, request));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove a product from the cart")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product removed from cart."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    public ResponseEntity<CartResponseDTO> removeCartItem(@PathVariable UUID productId) {
        return ResponseEntity.ok(cartService.removeItem(productId));
    }

    @DeleteMapping
    @Operation(summary = "Clear the cart")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Cart cleared successfully."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    public ResponseEntity<Void> clearCart() {
        cartService.clearCart();
        return ResponseEntity.noContent().build();
    }
}
