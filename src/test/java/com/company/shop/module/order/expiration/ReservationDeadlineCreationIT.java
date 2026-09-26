package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.stripe.model.PaymentIntent;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(ReservationDeadlineCreationIT.SkewedClockConfiguration.class)
class ReservationDeadlineCreationIT extends PostgresContainerSupport {
    private static final Duration APPLICATION_CLOCK_SKEW = Duration.ofHours(2);

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired ReservationExpirationWorkRepository workRepository;
    @Autowired ReservationExpirationProperties expirationProperties;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired UserRepository userRepository;
    @Autowired CartRepository cartRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock applicationClock;
    @MockitoBean CurrentUserFacade currentUserFacade;
    @MockitoBean StripePaymentIntentGateway stripeGateway;
    @MockitoBean ReservationExpirationPoller reservationExpirationPoller;

    @Test
    void checkout_shouldPersistDatabaseTimedDeadlineAndNeverRenewItOnIdempotentRetry() throws Exception {
        CheckoutInput input = checkoutInput();
        when(currentUserFacade.getCurrentUser()).thenReturn(
                new CurrentUserSnapshot(input.user().getId(), input.user().getEmail(), Set.of()));
        PaymentIntent providerIntent = org.mockito.Mockito.mock(PaymentIntent.class);
        when(providerIntent.getId()).thenReturn("pi_database_timed_reservation");
        when(providerIntent.getStatus()).thenReturn("requires_payment_method");
        when(providerIntent.getClientSecret()).thenReturn("cs_database_timed_reservation");
        when(stripeGateway.create(any(UUID.class), any(BigDecimal.class), any(String.class)))
                .thenReturn(providerIntent);

        Instant databaseTimeBeforeCheckout = workRepository.findCurrentTimestamp();
        Instant applicationTimeBeforeCheckout = applicationClock.instant();
        var initialResponse = orderService.placeOrderFromCart(
                input.checkoutKey(), new OrderCheckoutRequestDTO(null, null));
        Instant databaseTimeAfterCheckout = workRepository.findCurrentTimestamp();
        Instant applicationTimeAfterCheckout = applicationClock.instant();

        assertThat(Duration.between(databaseTimeBeforeCheckout, applicationTimeBeforeCheckout))
                .isGreaterThan(Duration.ofMinutes(90));
        Order initialOrder = orderRepository.findById(initialResponse.id()).orElseThrow();
        ReservationExpirationWork initialWork = workRepository.findByOrderId(initialOrder.getId()).orElseThrow();
        Instant initialDeadline = initialOrder.getReservationExpiresAt();
        assertThat(initialDeadline).isNotNull().isBetween(
                databaseTimeBeforeCheckout.plus(expirationProperties.duration()),
                databaseTimeAfterCheckout.plus(expirationProperties.duration()));
        assertThat(initialDeadline).isBefore(
                applicationTimeBeforeCheckout.plus(expirationProperties.duration()));
        assertThat(initialDeadline).isBefore(
                applicationTimeAfterCheckout.plus(expirationProperties.duration()));
        assertThat(initialWork.getDueAt()).isEqualTo(initialDeadline);
        assertThat(initialWork.getNextAttemptAt()).isEqualTo(initialDeadline);
        assertThat(workCount(initialOrder.getId())).isOne();

        UUID initialWorkId = initialWork.getId();
        applicationClock.advance(Duration.ofHours(4));
        var retryResponse = orderService.placeOrderFromCart(
                input.checkoutKey(), new OrderCheckoutRequestDTO(null, null));

        Order retriedOrder = orderRepository.findById(retryResponse.id()).orElseThrow();
        ReservationExpirationWork retriedWork = workRepository.findByOrderId(retriedOrder.getId()).orElseThrow();
        assertThat(retryResponse.id()).isEqualTo(initialResponse.id());
        assertThat(retriedOrder.getReservationExpiresAt()).isEqualTo(initialDeadline);
        assertThat(retriedWork.getId()).isEqualTo(initialWorkId);
        assertThat(retriedWork.getDueAt()).isEqualTo(initialDeadline);
        assertThat(retriedWork.getNextAttemptAt()).isEqualTo(initialDeadline);
        assertThat(workCount(initialOrder.getId())).isOne();
    }

    private CheckoutInput checkoutInput() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Category category = categoryRepository.saveAndFlush(new Category(
                "deadline-" + token, "deadline-" + token, "reservation deadline test"));
        Product product = productRepository.saveAndFlush(new Product(
                "Deadline " + token, "deadline-" + token, "DEADLINE-" + token,
                "reservation deadline test", BigDecimal.TEN, 2, category));
        User user = userRepository.saveAndFlush(new User(
                "deadline-" + token + "@example.com", "encoded", "Deadline", "Test"));
        Cart cart = new Cart(user);
        cart.addItem(product, 1);
        cartRepository.saveAndFlush(cart);
        return new CheckoutInput(user, "database-deadline-" + token);
    }

    private long workCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_expiration_work WHERE order_id = ?", Long.class, orderId);
    }

    private record CheckoutInput(User user, String checkoutKey) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class SkewedClockConfiguration {
        @Bean
        @Primary
        MutableClock skewedApplicationClock() {
            return new MutableClock(Clock.offset(Clock.systemUTC(), APPLICATION_CLOCK_SKEW).instant());
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
