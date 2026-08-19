package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.OrderPaymentNotAllowedException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.OrderService;
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
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReservationExpirationProperties expirationProperties;
    @Autowired Clock clock;
    @MockitoBean CurrentUserFacade currentUserFacade;
    @MockitoBean StripePaymentIntentGateway stripeGateway;

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
        PaymentIntent canceled = providerIntent("pi_expiration", "canceled");
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
        PaymentIntent canceled = providerIntent("pi_soft-delete-expiration", "canceled");
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
    private PaymentIntent providerIntent(String id, String status) {
        PaymentIntent intent = mock(PaymentIntent.class); when(intent.getId()).thenReturn(id); when(intent.getStatus()).thenReturn(status); return intent;
    }
    private void makeDue(Order order) {
        jdbcTemplate.update("UPDATE orders SET reservation_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", order.getId());
        jdbcTemplate.update("UPDATE reservation_expiration_work SET due_at = CURRENT_TIMESTAMP - INTERVAL '1 second', next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE order_id = ?", order.getId());
    }
    private int stock(UUID productId) { return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId); }
    private record Fixture(User user, Product product, Order order) {}
}
