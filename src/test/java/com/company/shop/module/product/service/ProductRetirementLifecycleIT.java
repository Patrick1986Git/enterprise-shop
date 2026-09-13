package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.cart.dto.CartResponseDTO;
import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.dto.UpdateCartItemRequestDTO;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.mapper.CartMapper;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.api.internal.ProductCatalogFacade;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
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
    @Autowired CartService cartService;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;

    @MockitoBean CurrentUserFacade currentUserFacade;
    @MockitoBean PaymentService paymentService;

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
        long persistedLines = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items WHERE product_id = ?",
                Long.class, fixture.productId());

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
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items WHERE product_id = ?", Long.class,
                fixture.productId())).isEqualTo(persistedLines);
        int stock = physical(fixture.productId()).stock();
        assertThatThrownBy(() -> transaction().execute(status ->
                productCatalogFacade.reserveProductForCheckout(fixture.productId(), 1)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThat(physical(fixture.productId()).stock()).isEqualTo(stock);
    }

    @Test
    void unavailableCartLine_shouldSupportUnrelatedAddAndExplicitRemovalButRejectQuantityMutation() {
        Fixture fixture = persistProductWithCart();
        UUID activeProductId = persistProduct("Available companion", "COMPANION", 5);
        authenticate(fixture);
        productService.delete(fixture.productId());

        assertThatThrownBy(() -> cartService.updateItemQuantity(fixture.productId(), new UpdateCartItemRequestDTO(4)))
                .isInstanceOf(ProductNotFoundException.class);
        assertThat(cartItemQuantity(fixture.userId(), fixture.productId())).isEqualTo(2);

        CartResponseDTO withCompanion = cartService.addToCart(new AddToCartRequestDTO(activeProductId, 1));
        assertThat(withCompanion.items()).extracting(item -> item.productId())
                .containsExactlyInAnyOrder(fixture.productId(), activeProductId);

        CartResponseDTO afterRemoval = cartService.removeItem(fixture.productId());
        assertThat(afterRemoval.items()).extracting(item -> item.productId()).containsExactly(activeProductId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_items WHERE product_id = ?", Long.class,
                fixture.productId())).isZero();
    }

    @Test
    void checkoutWithUnavailableLine_shouldFailBeforeStockOrderPaymentOrProviderMutation() {
        Fixture fixture = persistProductWithCart();
        UUID unrelatedProductId = persistProduct("Unrelated product", "UNRELATED", 1);
        persistUnrelatedOrderAndPayment(unrelatedProductId);
        authenticate(fixture);
        productService.delete(fixture.productId());
        PhysicalProduct before = physical(fixture.productId());
        Set<UUID> ordersBefore = persistedOrderIds();
        Set<UUID> paymentsBefore = persistedPaymentIds();

        assertThatThrownBy(() -> orderService.placeOrderFromCart("retired-product-checkout",
                new OrderCheckoutRequestDTO(null, null))).isInstanceOf(ProductNotFoundException.class);

        assertThat(physical(fixture.productId())).isEqualTo(before);
        assertThat(persistedOrderIds()).isEqualTo(ordersBefore);
        assertThat(persistedPaymentIds()).isEqualTo(paymentsBefore);
        verifyNoInteractions(paymentService);
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

    private UUID persistProduct(String name, String skuPrefix, int stock) {
        return transaction().execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Category category = new Category("Category " + suffix, "category-" + suffix, "desc");
            entityManager.persist(category);
            Product product = new Product(name, "product-" + suffix, skuPrefix + "-" + suffix, "desc",
                    BigDecimal.TEN, stock, category);
            entityManager.persist(product);
            entityManager.flush();
            return product.getId();
        });
    }

    private void persistUnrelatedOrderAndPayment(UUID unrelatedProductId) {
        transaction().executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User unrelatedUser = new User("unrelated-" + suffix + "@example.com", "password", "Unrelated", "User");
            entityManager.persist(unrelatedUser);
            Order order = new Order(unrelatedUser.getId(), unrelatedUser.getEmail(), "unrelated-checkout-" + suffix);
            order.addItem(new OrderItem(unrelatedProductId, "Unrelated product", "UNRELATED", 1, BigDecimal.TEN));
            entityManager.persist(order);
            entityManager.persist(new Payment(order, "STRIPE", order.getTotalAmount()));
            entityManager.flush();
        });
    }

    private Set<UUID> persistedOrderIds() {
        return orderRepository.findAll().stream().map(Order::getId).collect(java.util.stream.Collectors.toSet());
    }

    private Set<UUID> persistedPaymentIds() {
        return paymentRepository.findAll().stream().map(Payment::getId).collect(java.util.stream.Collectors.toSet());
    }

    private void authenticate(Fixture fixture) {
        when(currentUserFacade.getCurrentUser()).thenReturn(
                new CurrentUserSnapshot(fixture.userId(), "user@example.com", java.util.Set.of()));
    }

    private int cartItemQuantity(UUID userId, UUID productId) {
        return jdbcTemplate.queryForObject("""
                SELECT ci.quantity FROM cart_items ci
                JOIN carts c ON c.id = ci.cart_id
                WHERE c.user_id = ? AND ci.product_id = ?
                """, Integer.class, userId, productId);
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
