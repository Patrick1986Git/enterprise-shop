package com.company.shop.module.cart.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CartReconciliationConcurrencyIT extends PostgresContainerSupport {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void cartWriteLock_shouldPreserveMutationThatStartsWhilePaymentReconciliationIsInProgress() throws Exception {
        Category category = categoryRepository.saveAndFlush(new Category(
                "Cart lock diagnostic", "cart-lock-diagnostic", "Cart lock diagnostic"));
        Product paidProduct = productRepository.saveAndFlush(new Product(
                "Paid product", "paid-product-lock", "PAID-LOCK", "Paid product",
                BigDecimal.TEN, 10, category));
        Product laterProduct = productRepository.saveAndFlush(new Product(
                "Later product", "later-product-lock", "LATER-LOCK", "Later product",
                BigDecimal.TEN, 10, category));
        User user = userRepository.saveAndFlush(new User(
                "cart-lock@example.com", "encoded", "Cart", "Lock"));
        Cart cart = new Cart(user);
        cart.addItem(paidProduct, 1);
        cartRepository.saveAndFlush(cart);

        CountDownLatch reconciliationLocked = new CountDownLatch(1);
        CountDownLatch mutationReady = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        var reconciliation = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            Cart lockedCart = cartRepository.findByUserIdWithItemsForUpdate(user.getId()).orElseThrow();
            reconciliationLocked.countDown();
            await(mutationReady);
            lockedCart.reconcileItem(paidProduct.getId(), 1);
            cartRepository.save(lockedCart);
        }));
        var mutation = executor.submit(() -> {
            await(reconciliationLocked);
            mutationReady.countDown();
            transactionTemplate.executeWithoutResult(status -> {
                Cart lockedCart = cartRepository.findByUserIdWithItemsForUpdate(user.getId()).orElseThrow();
                lockedCart.addItem(laterProduct, 1);
                cartRepository.save(lockedCart);
            });
        });

        reconciliation.get(10, TimeUnit.SECONDS);
        mutation.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        Cart persistedCart = cartRepository.findByUserIdWithItems(user.getId()).orElseThrow();
        assertThat(persistedCart.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProduct().getId()).isEqualTo(laterProduct.getId());
            assertThat(item.getQuantity()).isEqualTo(1);
        });
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
