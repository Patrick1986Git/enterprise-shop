package com.company.shop.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.PersistenceUnitUtil;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CartRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByUserIdWithItems_shouldFetchItemsAndProductsWithCorrectData() {
        User user = PersistenceFixtures.persistUser(entityManager, "cart.repository@example.com");
        Cart cart = PersistenceFixtures.persistCart(entityManager, user);
        Product firstProduct = PersistenceFixtures.persistProduct(entityManager, "Phone", "phone", "SKU-PHONE", BigDecimal.valueOf(1999L), 15);
        Product secondProduct = PersistenceFixtures.persistProduct(entityManager, "Tablet", "tablet", "SKU-TABLET", BigDecimal.valueOf(999L), 20);

        cart.addItem(firstProduct, 1);
        cart.addItem(secondProduct, 2);
        entityManager.flush();
        entityManager.clear();

        Optional<Cart> found = cartRepository.findByUserIdWithItems(user.getId());

        assertThat(found).isPresent();
        Cart loadedCart = found.orElseThrow();
        assertThat(loadedCart.getUser().getId()).isEqualTo(user.getId());
        assertThat(loadedCart.getItems()).hasSize(2)
                .extracting(item -> item.getQuantity(), item -> item.getProduct().getSku(), item -> item.getProduct().getName())
                .containsExactlyInAnyOrder(
                        tuple(1, firstProduct.getSku(), firstProduct.getName()),
                        tuple(2, secondProduct.getSku(), secondProduct.getName()));

        PersistenceUnitUtil persistenceUnitUtil = entityManager.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistenceUnitUtil.isLoaded(loadedCart, "items")).isTrue();
        assertThat(loadedCart.getItems())
                .allSatisfy(item -> assertThat(persistenceUnitUtil.isLoaded(item, "product")).isTrue());
    }

    @Test
    void findByUserIdWithItems_shouldReturnEmptyWhenUserHasNoCart() {
        User userWithoutCart = PersistenceFixtures.persistUser(entityManager, "cart.missing@example.com");
        entityManager.clear();

        Optional<Cart> found = cartRepository.findByUserIdWithItems(userWithoutCart.getId());

        assertThat(found).isEmpty();
    }
}
