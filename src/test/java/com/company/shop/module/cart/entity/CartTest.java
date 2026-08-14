package com.company.shop.module.cart.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;

class CartTest {

    @Test
    void reconcileItem_shouldPreserveUnrelatedCartIntent() throws Exception {
        Product checkedOutProduct = product("A");
        Product unrelatedProduct = product("B");
        Cart cart = cartWith(unrelatedProduct, 1);

        cart.reconcileItem(checkedOutProduct.getId(), 1);

        assertThat(cart.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProduct().getId()).isEqualTo(unrelatedProduct.getId());
            assertThat(item.getQuantity()).isEqualTo(1);
        });
    }

    @Test
    void reconcileItem_shouldRemoveLineWhenCurrentQuantityIsFullyPaid() throws Exception {
        Product product = product("A");
        Cart cart = cartWith(product, 1);

        cart.reconcileItem(product.getId(), 1);

        assertThat(cart.getItems()).isEmpty();
    }

    @Test
    void reconcileItem_shouldPreserveQuantityAddedAfterCheckout() throws Exception {
        Product product = product("A");
        Cart cart = cartWith(product, 3);

        cart.reconcileItem(product.getId(), 1);

        assertThat(cart.getItems()).singleElement()
                .extracting(CartItem::getQuantity)
                .isEqualTo(2);
    }

    @Test
    void reconcileItem_shouldReconcileEachPaidLineAndPreserveUnrelatedLines() throws Exception {
        Product firstPaidProduct = product("A");
        Product secondPaidProduct = product("B");
        Product unrelatedProduct = product("C");
        Cart cart = cartWith(firstPaidProduct, 1);
        cart.addItem(secondPaidProduct, 4);
        cart.addItem(unrelatedProduct, 2);

        cart.reconcileItem(firstPaidProduct.getId(), 1);
        cart.reconcileItem(secondPaidProduct.getId(), 2);

        assertThat(cart.getItems())
                .extracting(item -> item.getProduct().getId(), CartItem::getQuantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(secondPaidProduct.getId(), 2),
                        org.assertj.core.groups.Tuple.tuple(unrelatedProduct.getId(), 2));
    }

    private Cart cartWith(Product product, int quantity) {
        Cart cart = new Cart(new User("cart-test@example.com", "encoded", "Cart", "Test"));
        cart.addItem(product, quantity);
        return cart;
    }

    private Product product(String suffix) throws Exception {
        Category category = new Category("Category " + suffix, "category-" + suffix.toLowerCase(), "description");
        Product product = new Product("Product " + suffix, "product-" + suffix.toLowerCase(), "SKU-" + suffix,
                "description", BigDecimal.TEN, 10, category);
        var idField = BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(product, UUID.randomUUID());
        return product;
    }
}
