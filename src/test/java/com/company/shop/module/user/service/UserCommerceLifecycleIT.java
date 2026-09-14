package com.company.shop.module.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.cart.dto.AddToCartRequestDTO;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.exception.UserNotFoundException;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.security.CurrentUserProvider;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserCommerceLifecycleIT extends PostgresContainerSupport {

    @Autowired private UserService userService;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;
    @Autowired private UserRepository userRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private CurrentUserProvider currentUserProvider;
    @MockitoBean private PaymentService paymentService;

    private final ThreadLocal<String> authenticatedEmail = new ThreadLocal<>();

    @BeforeEach
    void configureCurrentUser() {
        when(currentUserProvider.getCurrentUserEmail()).thenAnswer(invocation -> authenticatedEmail.get());
        when(paymentService.createPaymentIntent(any(Order.class)))
                .thenReturn(new PaymentIntentResponseDTO("pi_test", "pk_test"));
    }

    @AfterEach
    void clearCurrentUser() {
        authenticatedEmail.remove();
    }

    @Test
    void retirement_shouldKeepPhysicalForeignKeyButHideCartAndPreserveOrderSnapshots() {
        Fixture fixture = fixture(true);
        authenticatedEmail.set(fixture.user().getEmail());
        Order placed = orderRepository.findById(orderService.placeOrderFromCart(
                "history-key", new OrderCheckoutRequestDTO(null, null)).id()).orElseThrow();
        UUID cartId = fixture.cartId();
        UUID orderId = placed.getId();
        UUID userId = fixture.user().getId();
        String email = fixture.user().getEmail();

        userService.delete(userId);
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("select deleted from users where id = ?", Boolean.class, userId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("select user_id from carts where id = ?", UUID.class, cartId)).isEqualTo(userId);
        assertThat(cartRepository.findById(cartId)).isEmpty();
        assertThatThrownBy(cartService::getMyCart).isInstanceOf(UserNotFoundException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders where id = ?", Long.class, orderId)).isOne();
        assertThat(orderService.findAll(Pageable.unpaged()).getContent())
                .anySatisfy(order -> assertThat(order.id()).isEqualTo(orderId));
        assertThat(jdbcTemplate.queryForObject("select user_id from orders where id = ?", UUID.class, orderId))
                .isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject("select user_email from orders where id = ?", String.class, orderId))
                .isEqualTo(email);
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_items where order_id = ?", Long.class, orderId))
                .isOne();
    }

    @Test
    void retirementWinning_shouldRejectFirstCartCreationMutationAndCheckoutWithoutPartialState() throws Exception {
        Fixture noCart = fixture(false);
        assertRetirementWins(noCart, () -> cartService.getMyCart());
        assertThat(physicalCartCount(noCart.user().getId())).isZero();

        Fixture mutation = fixture(true);
        int quantityBefore = physicalCartQuantity(mutation.cartId());
        assertRetirementWins(mutation,
                () -> cartService.addToCart(new AddToCartRequestDTO(mutation.product().getId(), 1)));
        assertThat(physicalCartQuantity(mutation.cartId())).isEqualTo(quantityBefore);

        Fixture checkout = fixture(true);
        int stockBefore = physicalStock(checkout.product().getId());
        clearInvocations(paymentService);
        assertRetirementWins(checkout, () -> orderService.placeOrderFromCart(
                "retirement-first", new OrderCheckoutRequestDTO(null, null)));
        assertThat(physicalStock(checkout.product().getId())).isEqualTo(stockBefore);
        assertThat(count("orders", checkout.user().getId())).isZero();
        assertThat(countPayments(checkout.user().getId())).isZero();
        assertThat(countExpirationWork(checkout.user().getId())).isZero();
        assertThat(countOutbox(checkout.user().getId())).isZero();
        verifyNoInteractions(paymentService);
    }

    @Test
    void operationWinning_shouldCommitFirstCartMutationAndCheckoutBeforeRetirement() throws Exception {
        Fixture firstCart = fixture(false);
        assertOperationWins(firstCart, cartService::getMyCart);
        assertThat(physicalCartCount(firstCart.user().getId())).isOne();

        Fixture mutation = fixture(true);
        int before = physicalCartQuantity(mutation.cartId());
        assertOperationWins(mutation,
                () -> cartService.addToCart(new AddToCartRequestDTO(mutation.product().getId(), 1)));
        assertThat(physicalCartQuantity(mutation.cartId())).isEqualTo(before + 1);

        Fixture checkout = fixture(true);
        clearInvocations(paymentService);
        assertOperationWins(checkout, () -> orderService.placeOrderFromCart(
                "operation-first", new OrderCheckoutRequestDTO(null, null)));
        assertThat(count("orders", checkout.user().getId())).isOne();
        assertThat(countPayments(checkout.user().getId())).isOne();
        assertThat(countExpirationWork(checkout.user().getId())).isOne();
        assertThat(physicalStock(checkout.product().getId())).isEqualTo(9);
        verify(paymentService).createPaymentIntent(any(Order.class));
    }

    @Test
    void retirementRollback_shouldReleaseLifecycleLockAndPreserveActiveCart() {
        Fixture fixture = fixture(true);
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            userService.delete(fixture.user().getId());
            throw new ForcedRollback();
        })).isInstanceOf(ForcedRollback.class);

        authenticatedEmail.set(fixture.user().getEmail());
        assertThat(userRepository.findActiveById(fixture.user().getId())).isPresent();
        assertThat(cartService.addToCart(new AddToCartRequestDTO(fixture.product().getId(), 1)).totalItemsCount())
                .isEqualTo(2);
    }

    private void assertRetirementWins(Fixture fixture, Runnable operation) throws Exception {
        CountDownLatch retired = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
            userRepository.acquireCommerceLifecycleLock(fixture.user().getId());
            User active = userRepository.findActiveById(fixture.user().getId()).orElseThrow();
            active.markDeleted();
            userRepository.flush();
            retired.countDown();
            await(commit);
        }));
        assertThat(retired.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> loser = executor.submit(() -> transaction().executeWithoutResult(status -> {
            authenticatedEmail.set(fixture.user().getEmail());
            waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            operation.run();
        }));
        assertThat(awaitAdvisoryWait(waiterPid)).isTrue();
        commit.countDown();
        retirement.get(10, TimeUnit.SECONDS);
        assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(UserNotFoundException.class);
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private void assertOperationWins(Fixture fixture, Runnable operation) throws Exception {
        CountDownLatch operationDone = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        Future<?> winner = executor.submit(() -> transaction().executeWithoutResult(status -> {
            authenticatedEmail.set(fixture.user().getEmail());
            operation.run();
            operationDone.countDown();
            await(commit);
        }));
        assertThat(operationDone.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
            waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
            userService.delete(fixture.user().getId());
        }));
        assertThat(awaitAdvisoryWait(waiterPid)).isTrue();
        commit.countDown();
        winner.get(10, TimeUnit.SECONDS);
        retirement.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select deleted from users where id = ?", Boolean.class, fixture.user().getId())).isTrue();
    }

    private boolean awaitAdvisoryWait(AtomicInteger pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (select 1 from pg_locks locks
                    join pg_stat_activity activity on activity.pid = locks.pid
                    where locks.pid = ? and locks.locktype = 'advisory' and not locks.granted
                    and activity.wait_event_type = 'Lock' and activity.wait_event = 'advisory')
                    """, Boolean.class, pid.get()))) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private Fixture fixture(boolean withCart) {
        String suffix = UUID.randomUUID().toString();
        Category category = categoryRepository.saveAndFlush(new Category(
                "User lifecycle " + suffix, "user-lifecycle-" + suffix, "Lifecycle"));
        Product product = productRepository.saveAndFlush(new Product(
                "Lifecycle product", "lifecycle-product-" + suffix, "LIFE-" + suffix,
                "Lifecycle product", BigDecimal.TEN, 10, category));
        User user = userRepository.saveAndFlush(new User(
                "lifecycle-" + suffix + "@example.com", "encoded", "Life", "Cycle"));
        UUID cartId = null;
        if (withCart) {
            Cart cart = new Cart(user);
            cart.addItem(product, 1);
            cartId = cartRepository.saveAndFlush(cart).getId();
        }
        return new Fixture(user, product, cartId);
    }

    private long physicalCartCount(UUID userId) {
        return jdbcTemplate.queryForObject("select count(*) from carts where user_id = ?", Long.class, userId);
    }

    private int physicalCartQuantity(UUID cartId) {
        return jdbcTemplate.queryForObject("select quantity from cart_items where cart_id = ?", Integer.class, cartId);
    }

    private int physicalStock(UUID productId) {
        return jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private long count(String table, UUID userId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where user_id = ?", Long.class, userId);
    }

    private long countPayments(UUID userId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from payments p join orders o on o.id = p.order_id where o.user_id = ?
                """, Long.class, userId);
    }

    private long countExpirationWork(UUID userId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from reservation_expiration_work w join orders o on o.id = w.order_id
                where o.user_id = ?
                """, Long.class, userId);
    }

    private long countOutbox(UUID userId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from outbox_events e join orders o on cast(e.aggregate_id as uuid) = o.id
                where o.user_id = ?
                """, Long.class, userId);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("coordination timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(User user, Product product, UUID cartId) { }
    private static final class ForcedRollback extends RuntimeException { }
}
