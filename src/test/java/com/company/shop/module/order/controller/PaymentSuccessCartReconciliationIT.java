package com.company.shop.module.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureTestDatabase(replace = Replace.NONE)
class PaymentSuccessCartReconciliationIT extends PostgresContainerSupport {

    private static final String WEBHOOK_URL = "/api/v1/webhooks/stripe";

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void handleStripeWebhook_shouldPreserveCartIntentCreatedAfterEarlierCheckout() throws Exception {
        Category category = categoryRepository.saveAndFlush(new Category(
                "Payment reconciliation", "payment-reconciliation", "Payment reconciliation diagnostic"));
        Product checkoutProduct = productRepository.saveAndFlush(new Product(
                "Checkout product", "checkout-product", "CHECKOUT-A", "Product captured by the order",
                BigDecimal.valueOf(25), 10, category));
        Product postCheckoutProduct = productRepository.saveAndFlush(new Product(
                "Post-checkout product", "post-checkout-product", "POST-CHECKOUT-B",
                "Cart intent created after checkout", BigDecimal.valueOf(15), 10, category));
        User user = userRepository.saveAndFlush(new User(
                "payment-reconciliation@example.com", "encoded", "Payment", "Diagnostic"));

        Cart cart = new Cart(user);
        cart.addItem(checkoutProduct, 1);
        cartRepository.saveAndFlush(cart);

        Order order = new Order(user.getId(), user.getEmail(), "payment-reconciliation-checkout");
        order.addItem(new OrderItem(
                checkoutProduct.getId(), checkoutProduct.getName(), checkoutProduct.getSku(), 1,
                checkoutProduct.getPrice()));
        Order savedOrder = orderRepository.saveAndFlush(order);
        Payment payment = new Payment(savedOrder, "STRIPE", savedOrder.getTotalAmount());
        payment.attachProviderPayment("pi_payment_reconciliation", "cs_payment_reconciliation");
        paymentRepository.saveAndFlush(payment);

        Cart mutableCart = cartRepository.findByUserIdWithItems(user.getId()).orElseThrow();
        mutableCart.clear();
        mutableCart.addItem(postCheckoutProduct, 1);
        cartRepository.saveAndFlush(mutableCart);

        assertThat(persistedCartQuantity(user, postCheckoutProduct))
                .as("product B must be durable before the delayed payment-success webhook")
                .isEqualTo(1);

        Event event = succeededEvent(savedOrder, "evt_payment_reconciliation", "pi_payment_reconciliation");
        try (var webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
                    .thenReturn(event);

            mockMvc.perform(post(WEBHOOK_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Stripe-Signature", "sig")
                    .content("payload"))
                    .andExpect(status().isOk());
        }

        Order persistedOrder = orderRepository.findById(savedOrder.getId()).orElseThrow();
        Payment persistedPayment = paymentRepository.findByOrderId(savedOrder.getId()).orElseThrow();

        assertThat(persistedOrder.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(persistedPayment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(persistedCartQuantity(user, postCheckoutProduct))
                .as("a successful payment for product A must not delete post-checkout cart intent for product B")
                .isEqualTo(1);
    }

    @Test
    void handleStripeWebhook_shouldReconcileCheckedOutQuantityOnlyOnceWhenSucceededEventIsRepeated() throws Exception {
        Category category = categoryRepository.saveAndFlush(new Category(
                "Payment quantity reconciliation", "payment-quantity-reconciliation", "Quantity diagnostic"));
        Product product = productRepository.saveAndFlush(new Product(
                "Quantity product", "quantity-product", "QUANTITY-A", "Quantity reconciliation product",
                BigDecimal.valueOf(20), 10, category));
        User user = userRepository.saveAndFlush(new User(
                "payment-quantity-reconciliation@example.com", "encoded", "Payment", "Quantity"));

        Cart cart = new Cart(user);
        cart.addItem(product, 3);
        cartRepository.saveAndFlush(cart);

        Order order = new Order(user.getId(), user.getEmail(), "payment-quantity-checkout");
        order.addItem(new OrderItem(product.getId(), product.getName(), product.getSku(), 1, product.getPrice()));
        Order savedOrder = orderRepository.saveAndFlush(order);
        Payment payment = new Payment(savedOrder, "STRIPE", savedOrder.getTotalAmount());
        payment.attachProviderPayment("pi_payment_quantity", "cs_payment_quantity");
        paymentRepository.saveAndFlush(payment);

        Event event = succeededEvent(savedOrder, "evt_payment_quantity", "pi_payment_quantity");
        try (var webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
                    .thenReturn(event);

            performWebhook();
            performWebhook();
        }

        assertThat(orderRepository.findById(savedOrder.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(paymentRepository.findByOrderId(savedOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        assertThat(persistedCartQuantity(user, product))
                .as("a duplicate success webhook must not subtract the paid quantity twice")
                .isEqualTo(2);
    }

    private Event succeededEvent(Order order, String eventId, String paymentIntentId) {
        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        PaymentIntent paymentIntent = mock(PaymentIntent.class);

        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn("payment_intent.succeeded");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(java.util.Optional.of(paymentIntent));
        when(paymentIntent.getMetadata()).thenReturn(Map.of("orderId", order.getId().toString()));
        when(paymentIntent.getId()).thenReturn(paymentIntentId);
        when(paymentIntent.getAmountReceived()).thenReturn(order.getTotalAmount().movePointRight(2).longValueExact());
        when(paymentIntent.getCurrency()).thenReturn("pln");

        return event;
    }

    private void performWebhook() throws Exception {
        mockMvc.perform(post(WEBHOOK_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Stripe-Signature", "sig")
                .content("payload"))
                .andExpect(status().isOk());
    }

    private int persistedCartQuantity(User user, Product product) {
        Integer quantity = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(ci.quantity), 0) FROM cart_items ci "
                        + "JOIN carts c ON c.id = ci.cart_id WHERE c.user_id = ? AND ci.product_id = ?",
                Integer.class,
                user.getId(),
                product.getId());
        return quantity == null ? 0 : quantity;
    }
}
