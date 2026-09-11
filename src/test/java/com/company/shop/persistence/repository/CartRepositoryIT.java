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
import org.springframework.context.annotation.Import;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.service.ProductCatalogFacadeImpl;
import com.company.shop.module.user.entity.User;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.PersistenceUnitUtil;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(ProductCatalogFacadeImpl.class)
class CartRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ProductCatalogFacade productCatalogFacade;

    @Test
    void productResolvedForCart_shouldRemainManagedAndPersistCartItemRelationship() {
        User user = PersistenceFixtures.persistUser(entityManager, "cart.product.boundary@example.com");
        Cart cart = PersistenceFixtures.persistCart(entityManager, user);
        Product persistedProduct = PersistenceFixtures.persistProduct(
                entityManager, "Boundary product", "boundary-product", "SKU-BOUNDARY", BigDecimal.TEN, 7);
        entityManager.flush();
        entityManager.clear();

        Product resolvedProduct = productCatalogFacade.resolveProductForCart(persistedProduct.getId());
        cart = cartRepository.findById(cart.getId()).orElseThrow();
        cart.addItem(resolvedProduct, 2);
        cartRepository.saveAndFlush(cart);
        entityManager.clear();

        Cart reloaded = cartRepository.findByUserIdWithItems(user.getId()).orElseThrow();
        assertThat(reloaded.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProduct().getId()).isEqualTo(persistedProduct.getId());
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

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
