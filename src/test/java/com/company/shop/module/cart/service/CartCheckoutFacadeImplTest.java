package com.company.shop.module.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.cart.api.internal.CartCheckoutSnapshot;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;

@ExtendWith(MockitoExtension.class)
class CartCheckoutFacadeImplTest {

    @Mock
    private CartService cartService;

    @Test
    void getCartForCheckout_shouldReturnSnapshotWithProductIdsAndQuantities() throws Exception {
        CartCheckoutFacadeImpl facade = new CartCheckoutFacadeImpl(cartService);
        User user = new User("john@example.com", "pw", "John", "Doe");
        UUID userId = UUID.randomUUID();
        setId(user, userId);

        Product first = product("A", BigDecimal.TEN, 3);
        Product second = product("B", BigDecimal.ONE, 5);

        Cart cart = new Cart(user);
        setId(cart, UUID.randomUUID());
        cart.addItem(first, 2);
        cart.addItem(second, 4);

        when(cartService.getCartEntityForUser(userId)).thenReturn(cart);

        CartCheckoutSnapshot snapshot = facade.getCartForCheckout(userId);

        assertThat(snapshot.cartId()).isEqualTo(cart.getId());
        assertThat(snapshot.items()).hasSize(2);
        assertThat(snapshot.items()).extracting("productId").containsExactly(first.getId(), second.getId());
        assertThat(snapshot.items()).extracting("quantity").containsExactly(2, 4);
    }

    @Test
    void getCartForCheckout_shouldReturnEmptySnapshotForEmptyCart() throws Exception {
        CartCheckoutFacadeImpl facade = new CartCheckoutFacadeImpl(cartService);
        User user = new User("john@example.com", "pw", "John", "Doe");
        UUID userId = UUID.randomUUID();
        setId(user, userId);
        Cart cart = new Cart(user);
        setId(cart, UUID.randomUUID());

        when(cartService.getCartEntityForUser(userId)).thenReturn(cart);

        CartCheckoutSnapshot snapshot = facade.getCartForCheckout(userId);

        assertThat(snapshot.items()).isEmpty();
        assertThat(snapshot.isEmpty()).isTrue();
    }

    private Product product(String suffix, BigDecimal price, int stock) throws Exception {
        Category category = new Category("Cat-" + suffix, "cat-" + suffix, "desc");
        Product product = new Product("Prod-" + suffix, "prod-" + suffix, "SKU-" + suffix, "desc", price, stock, category);
        setId(product, UUID.randomUUID());
        return product;
    }

    private void setId(Object entity, UUID id) throws Exception {
        var field = BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
