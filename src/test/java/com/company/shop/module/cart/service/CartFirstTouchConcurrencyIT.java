package com.company.shop.module.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.api.internal.CurrentUserAssociationFacade;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CartFirstTouchConcurrencyIT extends PostgresContainerSupport {

    @Autowired private CartService cartService;
    @Autowired private CartRepository cartRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    @MockitoBean private CurrentUserFacade currentUserFacade;
    @MockitoBean private CurrentUserAssociationFacade currentUserAssociationFacade;
    @MockitoBean private ProductCatalogFacade productCatalogFacade;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        Category category = categoryRepository.saveAndFlush(new Category(
                "First touch " + suffix, "first-touch-" + suffix, "First touch"));
        product = productRepository.saveAndFlush(new Product(
                "First touch product", "first-touch-product-" + suffix, "FIRST-" + suffix,
                "First touch product", BigDecimal.TEN, 10, category));
        user = userRepository.saveAndFlush(new User(
                "first-touch-" + suffix + "@example.com", "encoded", "First", "Touch"));

        when(currentUserFacade.getCurrentUser())
                .thenReturn(new CurrentUserSnapshot(user.getId(), user.getEmail(), Set.of("USER")));
        when(currentUserAssociationFacade.getCurrentUserForAssociation()).thenReturn(user);
        when(productCatalogFacade.resolveProductForCart(product.getId())).thenReturn(product);
    }

    @Test
    void getMyCart_shouldUseWritableTransactionAndPersistMissingCartAfterCompletion() {
        when(currentUserFacade.getCurrentUser()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return new CurrentUserSnapshot(user.getId(), user.getEmail(), Set.of("USER"));
        });

        var response = cartService.getMyCart();

        assertThat(response.items()).isEmpty();
        assertThat(response.id()).isNotNull();
        transactionTemplate.executeWithoutResult(status -> entityManager.clear());
        assertThat(cartRepository.findByUserIdWithItems(user.getId()))
                .isPresent()
                .get()
                .extracting(Cart::getId)
                .isEqualTo(response.id());
    }

    @Test
    void twoConcurrentFirstAdds_shouldPersistEveryMutationInExactlyOneCart() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        var first = executor.submit(() -> addAfterBarrier(ready, start));
        var second = executor.submit(() -> addAfterBarrier(ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(List.of(
                first.get(10, TimeUnit.SECONDS).totalItemsCount(),
                second.get(10, TimeUnit.SECONDS).totalItemsCount()))
                .containsExactlyInAnyOrder(1, 2);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(cartRepository.countByUserId(user.getId())).isEqualTo(1);
        Cart persisted = cartRepository.findByUserIdWithItems(user.getId()).orElseThrow();
        assertThat(persisted.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProduct().getId()).isEqualTo(product.getId());
            assertThat(item.getQuantity()).isEqualTo(2);
        });
    }

    private com.company.shop.module.cart.dto.CartResponseDTO addAfterBarrier(
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        return cartService.addToCart(new AddToCartRequestDTO(product.getId(), 1));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent cart operation");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for concurrent cart operation", ex);
        }
    }
}
