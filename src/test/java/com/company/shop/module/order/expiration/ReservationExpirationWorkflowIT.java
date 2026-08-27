package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.PaymentProcessingException;
import com.company.shop.module.order.exception.OrderPaymentNotAllowedException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.service.PaymentInitializationTransactionService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.stripe.model.PaymentIntent;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ReservationExpirationWorkflowIT extends PostgresContainerSupport {
    @Autowired OrderService orderService;
    @Autowired ReservationExpirationProcessor processor;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductService productService;
    @Autowired UserRepository userRepository;
    @Autowired CartRepository cartRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentService paymentService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReservationExpirationProperties expirationProperties;
    @Autowired Clock clock;
    @MockitoBean CurrentUserFacade currentUserFacade;
    @MockitoBean StripePaymentIntentGateway stripeGateway;
    @MockitoSpyBean PaymentInitializationTransactionService paymentInitialization;

    @Test
    void paymentInitialization_shouldConvergeOnOriginalIntentAfterProviderSuccessAndLostAttach() throws Exception {
        CheckoutInput input = checkoutInput("lost-attach-retry", 1);
        PaymentIntent providerIntent = providerIntent("pi_lost_attach_retry", "requires_payment_method");
        when(providerIntent.getClientSecret()).thenReturn("cs_lost_attach_retry");
        AtomicInteger logicalProviderCreations = idempotentProvider(providerIntent, input);

        doThrow(new IllegalStateException("simulated process failure before attach commit"))
                .doCallRealMethod()
                .when(paymentInitialization).attach(any(UUID.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> orderService.placeOrderFromCart(input.checkoutKey(),
                new OrderCheckoutRequestDTO(null, null))).isInstanceOf(PaymentProcessingException.class);

        Order order = orderRepository.findByUserIdAndCheckoutIdempotencyKey(input.user().getId(), input.checkoutKey())
                .orElseThrow();
        var paymentAfterFailure = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(paymentAfterFailure.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentAfterFailure.getProviderPaymentId()).isNull();
        assertThat(paymentAfterFailure.getClientSecret()).isNull();
        assertThat(stock(input.product().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_expiration_work WHERE order_id = ?", Long.class, order.getId()))
                .isOne();

        var retry = paymentService.createPaymentIntent(order);

        var attached = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(retry.clientSecret()).isEqualTo("cs_lost_attach_retry");
        assertThat(attached.getProviderPaymentId()).isEqualTo("pi_lost_attach_retry");
        assertThat(attached.getClientSecret()).isEqualTo("cs_lost_attach_retry");
        assertThat(attached.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(logicalProviderCreations).hasValue(1);
        verify(stripeGateway, times(2)).create(eq(order.getId()), eq(order.getTotalAmount()),
                eq("order-payment-intent-" + order.getId()));
    }

    @Test
    void expiration_shouldRecoverOriginalIntentAfterProviderSuccessAndLostAttach() throws Exception {
        CheckoutInput input = checkoutInput("lost-attach-expiration", 1);
        PaymentIntent providerIntent = canceledProviderIntent("pi_lost_attach_expiration", BigDecimal.TEN);
        when(providerIntent.getClientSecret()).thenReturn("cs_lost_attach_expiration");
        AtomicInteger logicalProviderCreations = idempotentProvider(providerIntent, input);
        when(stripeGateway.retrieve("pi_lost_attach_expiration")).thenReturn(providerIntent);
        doThrow(new IllegalStateException("simulated process failure before attach commit"))
                .doCallRealMethod()
                .when(paymentInitialization).attach(any(UUID.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> orderService.placeOrderFromCart(input.checkoutKey(),
                new OrderCheckoutRequestDTO(null, null))).isInstanceOf(PaymentProcessingException.class);
        Order order = orderRepository.findByUserIdAndCheckoutIdempotencyKey(input.user().getId(), input.checkoutKey())
                .orElseThrow();
        makeDue(order);

        processor.processDueBatch();

        var payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getProviderPaymentId()).isEqualTo("pi_lost_attach_expiration");
        assertThat(payment.getClientSecret()).isEqualTo("cs_lost_attach_expiration");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(input.product().getId())).isEqualTo(1);
        assertThat(logicalProviderCreations).hasValue(1);
        verify(stripeGateway, times(2)).create(eq(order.getId()), eq(order.getTotalAmount()),
                eq("order-payment-intent-" + order.getId()));
        verify(stripeGateway).retrieve("pi_lost_attach_expiration");
    }

    @Test
    void expiration_shouldCancelProviderThenRestoreInventoryAndRejectSameCheckoutKey() throws Exception {
        Instant beforeCheckout = clock.instant();
        Fixture fixture = checkout("expiration", 1);
        Instant afterCheckout = clock.instant();
        Order order = fixture.order();
        assertThat(order.getReservationExpiresAt()).isNotNull();
        assertThat(order.getReservationExpiresAt()).isBetween(
                beforeCheckout.plus(expirationProperties.duration()),
                afterCheckout.plus(expirationProperties.duration()));
        assertThat(stock(fixture.product().getId())).isZero();

        makeDue(order);
        PaymentIntent canceled = canceledProviderIntent("pi_expiration", order);
        when(stripeGateway.retrieve("pi_expiration")).thenReturn(canceled);
        processor.processDueBatch();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stock(fixture.product().getId())).isEqualTo(1);
        assertThatThrownBy(() -> orderService.placeOrderFromCart("expiration-key", new OrderCheckoutRequestDTO(null, null)))
                .isInstanceOf(OrderPaymentNotAllowedException.class);
        assertThat(orderRepository.findAll()).filteredOn(o -> o.getUserId().equals(fixture.user().getId())).hasSize(1);
    }

    @Test
    void expiration_shouldRestoreSoftDeletedProductWithoutMakingItCatalogVisible() throws Exception {
        Fixture fixture = checkout("soft-delete-expiration", 2);
        assertThat(stock(fixture.product().getId())).isZero();
        productService.delete(fixture.product().getId());
        assertThat(productRepository.findById(fixture.product().getId())).isEmpty();
        assertThat(stock(fixture.product().getId())).isZero();
        makeDue(fixture.order());
        PaymentIntent canceled = canceledProviderIntent("pi_soft-delete-expiration", fixture.order());
        when(stripeGateway.retrieve("pi_soft-delete-expiration")).thenReturn(canceled);

        processor.processDueBatch();

        assertThat(orderRepository.findById(fixture.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderId(fixture.order().getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(stock(fixture.product().getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM products WHERE id = ?", Boolean.class,
                fixture.product().getId())).isTrue();
        assertThat(productRepository.findById(fixture.product().getId())).isEmpty();
    }

    private Fixture checkout(String suffix, int stock) throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String unique = suffix + "-" + token;
        String sku = "expiry-" + token;
        Category category = categoryRepository.saveAndFlush(new Category(unique, unique, "expiration test"));
        Product product = productRepository.saveAndFlush(new Product(unique, unique, sku, "expiration test",
                BigDecimal.TEN, stock, category));
        User user = userRepository.saveAndFlush(new User(unique + "@example.com", "encoded", "Expiry", "User"));
        Cart cart = new Cart(user); cart.addItem(product, stock); cartRepository.saveAndFlush(cart);
        when(currentUserFacade.getCurrentUser()).thenReturn(new CurrentUserSnapshot(user.getId(), user.getEmail(), Set.of()));
        PaymentIntent provider = providerIntent("pi_" + suffix, "requires_payment_method");
        when(provider.getClientSecret()).thenReturn("cs_" + suffix);
        when(stripeGateway.create(any(UUID.class), any(BigDecimal.class), any(String.class))).thenReturn(provider);
        var response = orderService.placeOrderFromCart(suffix + "-key", new OrderCheckoutRequestDTO(null, null));
        return new Fixture(user, product, orderRepository.findById(response.id()).orElseThrow());
    }
    private CheckoutInput checkoutInput(String suffix, int stock) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String unique = suffix + "-" + token;
        Category category = categoryRepository.saveAndFlush(new Category(unique, unique, "recovery test"));
        Product product = productRepository.saveAndFlush(new Product(unique, unique, "recovery-" + token,
                "recovery test", BigDecimal.TEN, stock, category));
        User user = userRepository.saveAndFlush(new User(unique + "@example.com", "encoded", "Recovery", "User"));
        Cart cart = new Cart(user);
        cart.addItem(product, stock);
        cartRepository.saveAndFlush(cart);
        when(currentUserFacade.getCurrentUser()).thenReturn(
                new CurrentUserSnapshot(user.getId(), user.getEmail(), Set.of()));
        return new CheckoutInput(user, product, suffix + "-key", BigDecimal.TEN.multiply(BigDecimal.valueOf(stock)));
    }
    private AtomicInteger idempotentProvider(PaymentIntent intent, CheckoutInput input) throws Exception {
        AtomicReference<UUID> firstOrderId = new AtomicReference<>();
        AtomicReference<String> firstKey = new AtomicReference<>();
        AtomicInteger logicalCreations = new AtomicInteger();
        when(stripeGateway.create(any(UUID.class), any(BigDecimal.class), any(String.class))).thenAnswer(invocation -> {
            UUID orderId = invocation.getArgument(0);
            BigDecimal amount = invocation.getArgument(1);
            String key = invocation.getArgument(2);
            assertThat(amount).isEqualByComparingTo(input.amount());
            if (firstKey.compareAndSet(null, key)) {
                firstOrderId.set(orderId);
                logicalCreations.incrementAndGet();
            } else {
                assertThat(orderId).isEqualTo(firstOrderId.get());
                assertThat(key).isEqualTo(firstKey.get());
            }
            return intent;
        });
        return logicalCreations;
    }
    private PaymentIntent providerIntent(String id, String status) {
        PaymentIntent intent = mock(PaymentIntent.class); when(intent.getId()).thenReturn(id); when(intent.getStatus()).thenReturn(status); return intent;
    }
    private PaymentIntent canceledProviderIntent(String id, Order order) {
        return canceledProviderIntent(id, order.getTotalAmount());
    }
    private PaymentIntent canceledProviderIntent(String id, BigDecimal amount) {
        PaymentIntent intent = providerIntent(id, "canceled");
        when(intent.getAmount()).thenReturn(amount.movePointRight(2).longValueExact());
        when(intent.getCurrency()).thenReturn("pln");
        return intent;
    }
    private void makeDue(Order order) {
        jdbcTemplate.update("UPDATE orders SET reservation_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", order.getId());
        jdbcTemplate.update("UPDATE reservation_expiration_work SET due_at = CURRENT_TIMESTAMP - INTERVAL '1 second', next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE order_id = ?", order.getId());
    }
    private int stock(UUID productId) { return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId); }
    private record Fixture(User user, Product product, Order order) {}
    private record CheckoutInput(User user, Product product, String checkoutKey, BigDecimal amount) {}
}
