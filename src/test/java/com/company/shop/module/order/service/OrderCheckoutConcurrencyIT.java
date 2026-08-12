package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.OrderInsufficientStockException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.OptimisticLockException;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OrderCheckoutConcurrencyIT extends PostgresContainerSupport {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CurrentUserFacade currentUserFacade;

    @MockitoBean
    private PaymentService paymentService;

    private final ThreadLocal<User> currentUser = new ThreadLocal<>();

    @BeforeEach
    void setUp() {
        truncateTestData();
        // Stub current-user resolution only to decouple this test from security plumbing.
        when(currentUserFacade.getCurrentUser()).thenAnswer(invocation -> {
            User user = currentUser.get();
            return new CurrentUserSnapshot(user.getId(), user.getEmail(), java.util.Set.of());
        });
        // Stub Stripe integration because this test verifies DB locking and stock consistency.
        when(paymentService.createPaymentIntent(any(Order.class)))
                .thenReturn(new com.company.shop.module.order.dto.PaymentIntentResponseDTO("pi_test", "pk_test"));
    }

    @AfterEach
    void tearDown() {
        currentUser.remove();
    }

    @Test
    void placeOrderFromCart_shouldCreateOnlyOneDurableReservationWhenUnchangedCartIsRetried() {
        Category category = categoryRepository.saveAndFlush(new Category("Retry Phones", "retry-phones",
                "Phones category for checkout retry diagnostic"));
        Product product = productRepository.saveAndFlush(new Product(
                "Retry Phone",
                "retry-phone",
                "RETRY-1",
                "Phone for checkout retry diagnostic",
                BigDecimal.valueOf(1999),
                10,
                category));
        User user = userRepository.saveAndFlush(new User("retry-user@example.com", "encoded", "Retry", "User"));
        createCartWithSingleItem(user, product, 2);
        currentUser.set(user);

        orderService.placeOrderFromCart("checkout-key", new OrderCheckoutRequestDTO(null, null));
        orderService.placeOrderFromCart("checkout-key", new OrderCheckoutRequestDTO(null, null));

        PersistedCheckoutState persistedState = new PersistedCheckoutState(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, user.getId()),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class),
                jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Long.class, product.getId()),
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE product_id = ?",
                        Long.class,
                        product.getId()),
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'OrderPlaced'",
                        Long.class));

        assertThat(persistedState)
                .as("an unchanged cart retry must not create a second durable checkout reservation")
                .isEqualTo(new PersistedCheckoutState(1L, 1L, 8L, 2L, 1L));
    }

    @Test
    void placeOrderFromCart_shouldAllowDistinctKeysForTheSameUnchangedCart() {
        Category category = categoryRepository.saveAndFlush(new Category("Distinct Phones", "distinct-phones",
                "Phones category for distinct checkout keys"));
        Product product = productRepository.saveAndFlush(new Product(
                "Distinct Phone", "distinct-phone", "DISTINCT-1", "Phone for distinct checkout keys",
                BigDecimal.valueOf(1999), 10, category));
        User user = userRepository.saveAndFlush(new User("distinct-user@example.com", "encoded", "Distinct", "User"));
        createCartWithSingleItem(user, product, 2);
        currentUser.set(user);

        orderService.placeOrderFromCart("checkout-key-one", new OrderCheckoutRequestDTO(null, null));
        orderService.placeOrderFromCart("checkout-key-two", new OrderCheckoutRequestDTO(null, null));

        assertThat(readPersistedCheckoutState(user, product))
                .as("different keys represent distinct logical checkouts even when the cart is unchanged")
                .isEqualTo(new PersistedCheckoutState(2L, 2L, 6L, 4L, 2L));
    }

    @Test
    void placeOrderFromCart_shouldCreateOneDurableReservationForConcurrentSameKeyRetries() throws Exception {
        Category category = categoryRepository.saveAndFlush(new Category("Concurrent Retry Phones",
                "concurrent-retry-phones", "Phones category for concurrent checkout retries"));
        Product product = productRepository.saveAndFlush(new Product(
                "Concurrent Retry Phone", "concurrent-retry-phone", "CONCURRENT-RETRY-1",
                "Phone for concurrent checkout retries", BigDecimal.valueOf(1999), 10, category));
        User user = userRepository.saveAndFlush(new User(
                "concurrent-retry-user@example.com", "encoded", "Concurrent", "Retry"));
        createCartWithSingleItem(user, product, 2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Future<CheckoutAttempt> firstFuture = executorService.submit(
                checkoutTask(user, "concurrent-checkout-key", ready, start));
        Future<CheckoutAttempt> secondFuture = executorService.submit(
                checkoutTask(user, "concurrent-checkout-key", ready, start));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<CheckoutAttempt> attempts = List.of(
                firstFuture.get(10, TimeUnit.SECONDS),
                secondFuture.get(10, TimeUnit.SECONDS));
        executorService.shutdown();
        assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(attempts).allMatch(CheckoutAttempt::success);
        assertThat(readPersistedCheckoutState(user, product))
                .isEqualTo(new PersistedCheckoutState(1L, 1L, 8L, 2L, 1L));
    }

    @Test
    void placeOrderFromCart_shouldAllowOnlyOneCheckoutWhenTwoUsersCompeteForLastStock() throws Exception {
        Category category = categoryRepository.saveAndFlush(new Category("Phones", "phones", "Phones category"));
        Product product = productRepository.saveAndFlush(new Product(
                "Edge Phone",
                "edge-phone",
                "EDGE-1",
                "Phone for concurrency test",
                BigDecimal.valueOf(1999),
                1,
                category));

        User firstUser = userRepository.saveAndFlush(new User("edge-user-1@example.com", "encoded", "Edge", "One"));
        User secondUser = userRepository.saveAndFlush(new User("edge-user-2@example.com", "encoded", "Edge", "Two"));

        createCartWithSingleItem(firstUser, product, 1);
        createCartWithSingleItem(secondUser, product, 1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        Callable<CheckoutAttempt> firstCheckout = checkoutTask(firstUser, "checkout-key", ready, start);
        Callable<CheckoutAttempt> secondCheckout = checkoutTask(secondUser, "checkout-key", ready, start);

        Future<CheckoutAttempt> firstFuture = executorService.submit(firstCheckout);
        Future<CheckoutAttempt> secondFuture = executorService.submit(secondCheckout);

        assertThat(ready.await(5, TimeUnit.SECONDS))
                .as("both checkout tasks should be ready before concurrent start")
                .isTrue();
        start.countDown();

        List<CheckoutAttempt> attempts = List.of(
                firstFuture.get(10, TimeUnit.SECONDS),
                secondFuture.get(10, TimeUnit.SECONDS));

        executorService.shutdown();
        assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        long successfulCheckouts = attempts.stream().filter(CheckoutAttempt::success).count();
        assertThat(successfulCheckouts)
                .as("exactly one checkout should succeed for stock=1")
                .isEqualTo(1);

        List<CheckoutAttempt> failedAttempts = attempts.stream()
                .filter(attempt -> !attempt.success())
                .toList();
        assertThat(failedAttempts)
                .as("exactly one checkout should fail; failures: %s", describeFailures(attempts))
                .hasSize(1);
        assertThat(failedAttempts.get(0).failure())
                .as("failed checkout should fail with known concurrency/business failure; failures: %s",
                        describeFailures(attempts))
                .isNotNull()
                .matches(this::isExpectedConcurrencyFailure);

        Product updatedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updatedProduct.getStock())
                .as("final stock should be 0 after one successful checkout")
                .isEqualTo(0);

        List<Order> createdOrders = orderRepository.findAll();
        assertThat(createdOrders)
                .as("only one order should be created when two users compete for last stock")
                .hasSize(1);
        Long orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        assertThat(orderCount).as("orders table should contain exactly one row").isEqualTo(1L);

        Long orderItemCountForProduct = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE product_id = ?",
                Long.class,
                product.getId());
        assertThat(orderItemCountForProduct)
                .as("order_items should contain exactly one row for checked out product")
                .isEqualTo(1L);
        Integer totalOrderedQuantity = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE product_id = ?",
                Integer.class,
                product.getId());
        assertThat(totalOrderedQuantity)
                .as("total ordered quantity for product should be exactly one (no quantity oversell)")
                .isEqualTo(1);

        List<Payment> createdPayments = paymentRepository.findAll();
        assertThat(createdPayments)
                .as("exactly one payment should exist for the single successful order")
                .hasSize(1);
        Payment payment = createdPayments.get(0);
        Order createdOrder = createdOrders.get(0);
        assertThat(payment.getOrder().getId()).isEqualTo(createdOrder.getId());
        assertThat(payment.getAmount()).isEqualByComparingTo(createdOrder.getTotalAmount());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private void createCartWithSingleItem(User user, Product product, int quantity) {
        Cart cart = new Cart(user);
        cart.addItem(product, quantity);
        cartRepository.saveAndFlush(cart);
    }

    private void truncateTestData() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    payments,
                    order_items,
                    orders,
                    outbox_events,
                    cart_items,
                    carts,
                    products,
                    categories,
                    users
                RESTART IDENTITY CASCADE
                """);
    }

    private Callable<CheckoutAttempt> checkoutTask(
            User user,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            currentUser.set(user);
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                return CheckoutAttempt.failed(new IllegalStateException("Timed out waiting for concurrent checkout start"));
            }
            try {
                orderService.placeOrderFromCart(idempotencyKey, new OrderCheckoutRequestDTO(null, null));
                return CheckoutAttempt.succeeded();
            } catch (Throwable throwable) {
                return CheckoutAttempt.failed(throwable);
            } finally {
                currentUser.remove();
            }
        };
    }

    private record CheckoutAttempt(boolean success, Throwable failure) {

        private static CheckoutAttempt succeeded() {
            return new CheckoutAttempt(true, null);
        }

        private static CheckoutAttempt failed(Throwable failure) {
            return new CheckoutAttempt(false, failure);
        }
    }

    private record PersistedCheckoutState(
            long orderCount,
            long paymentCount,
            long productStock,
            long orderedQuantity,
            long orderPlacedEventCount) {
    }

    private PersistedCheckoutState readPersistedCheckoutState(User user, Product product) {
        return new PersistedCheckoutState(
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Long.class, user.getId()),
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class),
                jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Long.class, product.getId()),
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE product_id = ?",
                        Long.class,
                        product.getId()),
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'OrderPlaced'",
                        Long.class));
    }

    private boolean isExpectedConcurrencyFailure(Throwable failure) {
        return hasCause(failure, OrderInsufficientStockException.class)
                || hasCause(failure, ObjectOptimisticLockingFailureException.class)
                || hasCause(failure, OptimisticLockingFailureException.class)
                || hasCause(failure, OptimisticLockException.class)
                || hasCause(failure, TransactionSystemException.class)
                || hasCause(failure, UnexpectedRollbackException.class);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> expectedType) {
        Throwable current = throwable;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String describeFailures(List<CheckoutAttempt> attempts) {
        return attempts.stream()
                .filter(attempt -> !attempt.success())
                .map(CheckoutAttempt::failure)
                .map(this::describeThrowable)
                .collect(Collectors.joining("; "));
    }

    private String describeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        StringBuilder description = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (description.length() > 0) {
                description.append(" -> ");
            }
            description.append(current.getClass().getName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                description.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return description.toString();
    }
}
