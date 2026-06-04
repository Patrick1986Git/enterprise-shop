package com.company.shop.persistence.constraint;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.entity.CartItem;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CartItemConstraintIT extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persist_shouldThrowWhenDuplicateProductExistsInSameCart() {
        User user = PersistenceFixtures.persistUser(entityManager, "cart.user@example.com");
        Cart cart = PersistenceFixtures.persistCart(entityManager, user);
        Product product = PersistenceFixtures.persistProduct(
                entityManager,
                "Headphones",
                "headphones",
                "SKU-HEADPHONES",
                BigDecimal.valueOf(99.99),
                20);

        CartItem firstItem = new CartItem(cart, product, 1);
        PersistenceFixtures.persistAndFlush(entityManager, firstItem);
        entityManager.clear();

        Cart managedCart = entityManager.getEntityManager().getReference(Cart.class, cart.getId());
        Product managedProduct = entityManager.getEntityManager().getReference(Product.class, product.getId());

        CartItem duplicateItem = new CartItem(managedCart, managedProduct, 2);
        PersistenceFixtures.setCreatedAt(duplicateItem);

        assertThatThrownBy(() -> {
            entityManager.persist(duplicateItem);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void persist_shouldThrowWhenQuantityIsNotPositive() {
        User user = PersistenceFixtures.persistUser(entityManager, "cart.check@example.com");
        Cart cart = PersistenceFixtures.persistCart(entityManager, user);
        Product product = PersistenceFixtures.persistProduct(
                entityManager,
                "Mouse",
                "mouse",
                "SKU-MOUSE",
                BigDecimal.valueOf(99.99),
                20);

        CartItem invalidItem = new CartItem(cart, product, 0);
        PersistenceFixtures.setCreatedAt(invalidItem);

        assertThatThrownBy(() -> {
            entityManager.persist(invalidItem);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasRootCauseInstanceOf(PSQLException.class);
    }
}
