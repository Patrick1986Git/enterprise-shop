package com.company.shop.module.cart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.support.WebMvcSliceTestConfig;
import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.dto.CartItemResponseDTO;
import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.dto.UpdateCartItemRequestDTO;
import com.company.shop.module.cart.exception.InsufficientStockException;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = CartController.class)
@Import(WebMvcSliceTestConfig.class)
class CartControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CartService cartService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Nested
    class GetCart {

        @Test
        void getCart_shouldReturnForbiddenForAnonymous() throws Exception {
            mockMvc.perform(get("/api/v1/me/cart"))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).getMyCart();
        }

        @Test
        void getCart_shouldReturnOkForAuthenticatedUserAndDelegateToService() throws Exception {
            CartResponseDTO response = sampleCartResponse();
            when(cartService.getMyCart()).thenReturn(response);

            mockMvc.perform(get("/api/v1/me/cart")
                            .with(user("user").roles("USER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(response.id().toString()))
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.items", not(empty())))
                    .andExpect(jsonPath("$.items[0].productId").value(response.items().get(0).productId().toString()))
                    .andExpect(jsonPath("$.items[0].productName").value("Gaming Mouse"))
                    .andExpect(jsonPath("$.items[0].productSlug").value("gaming-mouse"))
                    .andExpect(jsonPath("$.items[0].mainImageUrl").exists())
                    .andExpect(jsonPath("$.items[0].unitPrice").exists())
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.items[0].subtotal").exists())
                    .andExpect(jsonPath("$.items[0].stockAvailable").value(10))
                    .andExpect(jsonPath("$.items[0].isLowStock").value(false))
                    .andExpect(jsonPath("$.totalItemsCount").value(2))
                    .andExpect(jsonPath("$.totalAmount").exists());

            verify(cartService).getMyCart();
        }
    }

    @Nested
    class AddCartItem {

        @Test
        void addCartItem_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            AddToCartRequestDTO request = new AddToCartRequestDTO(UUID.randomUUID(), 2);

            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).addToCart(any(AddToCartRequestDTO.class));
        }

        @Test
        void addCartItem_shouldReturnForbiddenForAuthenticatedWhenCsrfMissing() throws Exception {
            AddToCartRequestDTO request = new AddToCartRequestDTO(UUID.randomUUID(), 2);

            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).addToCart(any(AddToCartRequestDTO.class));
        }

        @Test
        void addCartItem_shouldReturnOkWhenRequestValidAndDelegateExactPayloadToService() throws Exception {
            UUID productId = UUID.randomUUID();
            AddToCartRequestDTO request = new AddToCartRequestDTO(productId, 2);
            CartResponseDTO response = sampleCartResponse();

            when(cartService.addToCart(any(AddToCartRequestDTO.class))).thenReturn(response);

            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(response.id().toString()))
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.items", not(empty())))
                    .andExpect(jsonPath("$.items[0].productId").value("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
                    .andExpect(jsonPath("$.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.items[0].isLowStock").value(false))
                    .andExpect(jsonPath("$.totalItemsCount").value(response.totalItemsCount()))
                    .andExpect(jsonPath("$.totalAmount").exists());

            ArgumentCaptor<AddToCartRequestDTO> requestCaptor = ArgumentCaptor.forClass(AddToCartRequestDTO.class);
            verify(cartService).addToCart(requestCaptor.capture());

            AddToCartRequestDTO captured = requestCaptor.getValue();
            assertThat(captured.productId()).isEqualTo(productId);
            assertThat(captured.quantity()).isEqualTo(2);
        }

        @Test
        void addCartItem_shouldReturnBadRequestWhenProductIdIsNull() throws Exception {
            String invalidBody = """
                    {
                      "productId": null,
                      "quantity": 1
                    }
                    """;

            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors.productId").isArray())
                    .andExpect(jsonPath("$.errors.productId", not(empty())));

            verify(cartService, never()).addToCart(any(AddToCartRequestDTO.class));
        }

        @Test
        void addCartItem_shouldReturnBadRequestWhenQuantityIsLessThanOne() throws Exception {
            String invalidBody = """
                    {
                      "productId": "%s",
                      "quantity": 0
                    }
                    """.formatted(UUID.randomUUID());

            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").exists())
                    .andExpect(jsonPath("$.errors.quantity").isArray())
                    .andExpect(jsonPath("$.errors.quantity", not(empty())));

            verify(cartService, never()).addToCart(any(AddToCartRequestDTO.class));
        }

        @Test
        void addCartItem_shouldReturnBadRequestWhenBodyIsEmpty() throws Exception {
            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists());

            verify(cartService, never()).addToCart(any(AddToCartRequestDTO.class));
        }

        @Test
        void addCartItem_shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":\"not-closed\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists());

            verify(cartService, never()).addToCart(any(AddToCartRequestDTO.class));
        }

        @Test
        void addCartItem_shouldReturnNotFoundAndStableErrorShapeWhenProductDoesNotExist() throws Exception {
            UUID missingProductId = UUID.randomUUID();
            AddToCartRequestDTO request = new AddToCartRequestDTO(missingProductId, 1);

            when(cartService.addToCart(any(AddToCartRequestDTO.class)))
                    .thenThrow(new ProductNotFoundException(missingProductId));

            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(cartService).addToCart(any(AddToCartRequestDTO.class));
        }

        @Test
        void addCartItem_shouldReturnConflictAndStableErrorShapeWhenStockIsInsufficient() throws Exception {
            AddToCartRequestDTO request = new AddToCartRequestDTO(UUID.randomUUID(), 5);
            when(cartService.addToCart(any(AddToCartRequestDTO.class)))
                    .thenThrow(new InsufficientStockException(2));

            mockMvc.perform(post("/api/v1/me/cart/items")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(cartService).addToCart(any(AddToCartRequestDTO.class));
        }
    }

    @Nested
    class UpdateCartItemQuantity {

        @Test
        void updateCartItemQuantity_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", UUID.randomUUID())
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":2}"))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }

        @Test
        void updateCartItemQuantity_shouldReturnForbiddenForAuthenticatedWhenCsrfMissing() throws Exception {
            UUID productId = UUID.randomUUID();

            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":2}"))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }

        @Test
        void updateCartItemQuantity_shouldReturnOkWhenRequestValidAndDelegateExactPayloadToService() throws Exception {
            UUID productId = UUID.randomUUID();
            UpdateCartItemRequestDTO request = new UpdateCartItemRequestDTO(3);
            CartResponseDTO response = sampleCartResponse();

            when(cartService.updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class))).thenReturn(response);

            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(response.id().toString()))
                    .andExpect(jsonPath("$.totalItemsCount").value(response.totalItemsCount()));

            ArgumentCaptor<UUID> productIdCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<UpdateCartItemRequestDTO> requestCaptor = ArgumentCaptor.forClass(UpdateCartItemRequestDTO.class);
            verify(cartService).updateItemQuantity(productIdCaptor.capture(), requestCaptor.capture());

            assertThat(productIdCaptor.getValue()).isEqualTo(productId);
            assertThat(requestCaptor.getValue().quantity()).isEqualTo(3);
        }

        @Test
        void updateCartItemQuantity_shouldReturnBadRequestWhenQuantityIsLessThanOne() throws Exception {
            UUID productId = UUID.randomUUID();

            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").exists())
                    .andExpect(jsonPath("$.errors.quantity").isArray())
                    .andExpect(jsonPath("$.errors.quantity", not(empty())));

            verify(cartService, never()).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }

        @Test
        void updateCartItemQuantity_shouldReturnBadRequestWhenBodyIsEmpty() throws Exception {
            UUID productId = UUID.randomUUID();

            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists());

            verify(cartService, never()).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }

        @Test
        void updateCartItemQuantity_shouldReturnBadRequestWhenBodyIsEmptyJsonObject() throws Exception {
            UUID productId = UUID.randomUUID();

            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors.quantity").isArray())
                    .andExpect(jsonPath("$.errors.quantity", not(empty())));

            verify(cartService, never()).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }

        @Test
        void updateCartItemQuantity_shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
            UUID productId = UUID.randomUUID();

            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists());

            verify(cartService, never()).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }

        @Test
        void updateCartItemQuantity_shouldReturnBadRequestWhenProductIdIsInvalidUuid() throws Exception {
            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", "invalid-uuid")
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":2}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists());

            verify(cartService, never()).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }

        @Test
        void updateCartItemQuantity_shouldReturnConflictAndStableErrorShapeWhenStockIsInsufficient() throws Exception {
            UUID productId = UUID.randomUUID();
            UpdateCartItemRequestDTO request = new UpdateCartItemRequestDTO(10);

            when(cartService.updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class)))
                    .thenThrow(new InsufficientStockException(3));

            mockMvc.perform(patch("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(cartService).updateItemQuantity(any(UUID.class), any(UpdateCartItemRequestDTO.class));
        }
    }

    @Nested
    class RemoveCartItem {

        @Test
        void removeCartItem_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            mockMvc.perform(delete("/api/v1/me/cart/items/{productId}", UUID.randomUUID())
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).removeItem(any(UUID.class));
        }

        @Test
        void removeCartItem_shouldReturnForbiddenForAuthenticatedWhenCsrfMissing() throws Exception {
            mockMvc.perform(delete("/api/v1/me/cart/items/{productId}", UUID.randomUUID())
                            .with(user("user").roles("USER")))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).removeItem(any(UUID.class));
        }

        @Test
        void removeCartItem_shouldReturnOkWhenRequestValidAndDelegateExactProductIdToService() throws Exception {
            UUID productId = UUID.randomUUID();
            CartResponseDTO response = sampleCartResponse();

            when(cartService.removeItem(any(UUID.class))).thenReturn(response);

            mockMvc.perform(delete("/api/v1/me/cart/items/{productId}", productId)
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(response.id().toString()))
                    .andExpect(jsonPath("$.items").isArray())
                    .andExpect(jsonPath("$.totalItemsCount").value(response.totalItemsCount()))
                    .andExpect(jsonPath("$.totalAmount").exists());

            ArgumentCaptor<UUID> productIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(cartService).removeItem(productIdCaptor.capture());
            assertThat(productIdCaptor.getValue()).isEqualTo(productId);
        }

        @Test
        void removeCartItem_shouldReturnBadRequestWhenProductIdIsInvalidUuid() throws Exception {
            mockMvc.perform(delete("/api/v1/me/cart/items/{productId}", "invalid-uuid")
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists());

            verify(cartService, never()).removeItem(any(UUID.class));
        }
    }

    @Nested
    class ClearCart {

        @Test
        void clearCart_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            mockMvc.perform(delete("/api/v1/me/cart")
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).clearCart();
        }

        @Test
        void clearCart_shouldReturnForbiddenForAuthenticatedWhenCsrfMissing() throws Exception {
            mockMvc.perform(delete("/api/v1/me/cart")
                            .with(user("user").roles("USER")))
                    .andExpect(status().isForbidden());

            verify(cartService, never()).clearCart();
        }

        @Test
        void clearCart_shouldReturnNoContentWithEmptyBodyAndDelegateToService() throws Exception {
            mockMvc.perform(delete("/api/v1/me/cart")
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));

            verify(cartService).clearCart();
        }
    }

    private CartResponseDTO sampleCartResponse() {
        UUID cartId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID productId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        CartItemResponseDTO item = new CartItemResponseDTO(
                productId,
                "Gaming Mouse",
                "gaming-mouse",
                "https://cdn.example.com/images/gaming-mouse.jpg",
                new BigDecimal("99.99"),
                2,
                new BigDecimal("199.98"),
                10,
                false);

        return new CartResponseDTO(
                cartId,
                List.of(item),
                new BigDecimal("199.98"),
                2);
    }
}