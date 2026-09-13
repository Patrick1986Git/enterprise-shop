package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.mapper.CartMapper;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
class ProductRetirementLifecycleIT extends PostgresContainerSupport {

    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired ProductCatalogFacade productCatalogFacade;
    @Autowired CartRepository cartRepository;
    @Autowired CartMapper cartMapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void retirement_shouldHideProductAdvanceVersionAndRejectRepeatedRetirement() {
        UUID productId = persistProductWithCart().productId();
        PhysicalProduct before = physical(productId);

        productService.delete(productId);

        PhysicalProduct retired = physical(productId);
        assertThat(retired.deleted()).isTrue();
        assertThat(retired.deletedAtPresent()).isTrue();
        assertThat(retired.version()).isEqualTo(before.version() + 1);
        assertThat(retired.stock()).isEqualTo(before.stock());
        assertThat(productRepository.findById(productId)).isEmpty();
        assertThatThrownBy(() -> productService.delete(productId)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void retiredProduct_shouldRemainAsUnavailableCartLineAndFailReservationWithoutChangingStock() {
        Fixture fixture = persistProductWithCart();
        productService.delete(fixture.productId());

        CartResponseDTO response = transaction().execute(status -> {
            Cart cart = cartRepository.findByUserIdWithItems(fixture.userId()).orElseThrow();
            assertThat(cart.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(fixture.productId());
                assertThat(item.getProduct()).isNull();
            });
            return cartMapper.toDTO(cart);
        });

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.productId()).isEqualTo(fixture.productId());
            assertThat(item.available()).isFalse();
            assertThat(item.productName()).isNull();
            assertThat(item.unitPrice()).isNull();
            assertThat(item.subtotal()).isZero();
        });
        assertThat(response.totalAmount()).isZero();
        int stock = physical(fixture.productId()).stock();
        assertThatThrownBy(() -> transaction().execute(status ->
                productCatalogFacade.reserveProductForCheckout(fixture.productId(), 1)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThat(physical(fixture.productId()).stock()).isEqualTo(stock);
    }

    @Test
    void retirementRollback_shouldPreserveActiveRowAndVersion() {
        UUID productId = persistProductWithCart().productId();
        PhysicalProduct before = physical(productId);

        transaction().executeWithoutResult(status -> {
            productService.delete(productId);
            status.setRollbackOnly();
        });

        assertThat(physical(productId)).isEqualTo(before);
        assertThat(productRepository.findById(productId)).isPresent();
    }

    @Test
    void retirementWinningRowLock_shouldMakeConcurrentCatalogWriterObserveNotFound() throws Exception {
        Fixture fixture = persistProductWithCart();
        var initial = productService.findById(fixture.productId());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
                productRepository.findByIdWithLock(fixture.productId()).orElseThrow();
                locked.countDown();
                await(writerStarted);
                productService.delete(fixture.productId());
            }));
            var update = executor.submit(() -> {
                await(locked);
                writerStarted.countDown();
                return org.assertj.core.api.Assertions.catchThrowable(() -> productService.update(fixture.productId(),
                        new com.company.shop.module.product.dto.ProductUpdateDTO(initial.getVersion(), "Changed",
                                initial.getSku(), initial.getDescription(), initial.getPrice(),
                                initial.getCategoryId(), initial.getImageUrls())));
            });

            retirement.get(10, TimeUnit.SECONDS);
            assertThat(update.get(10, TimeUnit.SECONDS)).isInstanceOf(ProductNotFoundException.class);
        }
        assertThat(physical(fixture.productId()).deleted()).isTrue();
    }

    @Test
    void checkoutReservationWinningRowLock_shouldCommitStockBeforeRetirement() throws Exception {
        Fixture fixture = persistProductWithCart();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch retirementStarted = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var reservation = executor.submit(() -> transaction().execute(status -> {
                productRepository.findByIdWithLock(fixture.productId()).orElseThrow();
                locked.countDown();
                await(retirementStarted);
                return productCatalogFacade.reserveProductForCheckout(fixture.productId(), 2);
            }));
            var retirement = executor.submit(() -> {
                await(locked);
                retirementStarted.countDown();
                productService.delete(fixture.productId());
                return null;
            });

            assertThat(reservation.get(10, TimeUnit.SECONDS).id()).isEqualTo(fixture.productId());
            retirement.get(10, TimeUnit.SECONDS);
        }

        PhysicalProduct retired = physical(fixture.productId());
        assertThat(retired.deleted()).isTrue();
        assertThat(retired.stock()).isEqualTo(6);
        assertThat(retired.version()).isEqualTo(2);
    }

    private Fixture persistProductWithCart() {
        return transaction().execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Category category = new Category("Category " + suffix, "category-" + suffix, "desc");
            entityManager.persist(category);
            Product product = new Product("Product " + suffix, "product-" + suffix, "SKU-" + suffix, "desc",
                    BigDecimal.TEN, 8, category);
            entityManager.persist(product);
            User user = new User("user-" + suffix + "@example.com", "password", "Test", "User");
            entityManager.persist(user);
            Cart cart = new Cart(user);
            cart.addItem(product, 2);
            entityManager.persist(cart);
            entityManager.flush();
            return new Fixture(product.getId(), user.getId());
        });
    }

    private PhysicalProduct physical(UUID productId) {
        return jdbcTemplate.queryForObject("""
                SELECT version, stock, deleted, deleted_at IS NOT NULL
                FROM products WHERE id = ?
                """, (rs, row) -> new PhysicalProduct(rs.getLong(1), rs.getInt(2), rs.getBoolean(3), rs.getBoolean(4)),
                productId);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for barrier");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for barrier", ex);
        }
    }

    private record Fixture(UUID productId, UUID userId) { }
    private record PhysicalProduct(long version, int stock, boolean deleted, boolean deletedAtPresent) { }
}
