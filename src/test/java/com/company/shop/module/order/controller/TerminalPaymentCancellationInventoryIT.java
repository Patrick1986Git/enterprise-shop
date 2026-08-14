package com.company.shop.module.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.dto.OrderResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.repository.StripeWebhookEventRepository;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TerminalPaymentCancellationInventoryIT extends PostgresContainerSupport {

    private static final String WEBHOOK_URL = "/api/v1/webhooks/stripe";
    private static final String EVENT_ID = "evt_terminal_inventory_cancellation";
    private static final String EVENT_TYPE = "payment_intent.canceled";
    private static final String PAYMENT_INTENT_ID = "pi_terminal_inventory_cancellation";

    @Autowired
    private MockMvc mockMvc;

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
    private StripeWebhookEventRepository stripeWebhookEventRepository;

    @MockitoBean
    private CurrentUserFacade currentUserFacade;

    @Test
    void handleStripeWebhook_shouldReleaseReservedInventoryWhenPaymentIntentIsTerminallyCanceled() throws Exception {
        Category category = categoryRepository.saveAndFlush(new Category(
                "Terminal cancellation", "terminal-cancellation", "Terminal inventory cancellation diagnostic"));
        Product product = productRepository.saveAndFlush(new Product(
                "Reserved product", "reserved-product", "TERMINAL-CANCEL-A", "Reserved during checkout",
                BigDecimal.valueOf(25), 5, category));
        User user = userRepository.saveAndFlush(new User(
                "terminal-cancellation@example.com", "encoded", "Terminal", "Diagnostic"));
        Cart cart = new Cart(user);
        cart.addItem(product, 2);
        cartRepository.saveAndFlush(cart);
        when(currentUserFacade.getCurrentUser())
                .thenReturn(new CurrentUserSnapshot(user.getId(), user.getEmail(), Set.of()));

        assertThat(persistedStock(product)).as("initial PostgreSQL stock").isEqualTo(5);

        PaymentIntent providerIntent = mock(PaymentIntent.class);
        when(providerIntent.getId()).thenReturn(PAYMENT_INTENT_ID);
        when(providerIntent.getClientSecret()).thenReturn("cs_terminal_inventory_cancellation");

        OrderResponseDTO checkout;
        try (MockedStatic<PaymentIntent> paymentIntentStatic = mockStatic(PaymentIntent.class)) {
            paymentIntentStatic.when(() -> PaymentIntent.create(
                    any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(providerIntent);
            checkout = orderService.placeOrderFromCart(
                    "terminal-cancellation-checkout", new OrderCheckoutRequestDTO(null, null));
        }

        Order order = orderRepository.findById(checkout.id()).orElseThrow();
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getProviderPaymentId()).isEqualTo(PAYMENT_INTENT_ID);
        assertThat(payment.getClientSecret()).isEqualTo("cs_terminal_inventory_cancellation");
        assertThat(persistedStock(product)).as("stock after the real checkout reservation").isEqualTo(3);

        Event canceledEvent = canceledEvent(order);
        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
                    .thenReturn(canceledEvent);

            mockMvc.perform(post(WEBHOOK_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Stripe-Signature", "sig")
                    .content("payload"))
                    .andExpect(status().isOk());
        }

        Order orderAfterCancellation = orderRepository.findById(order.getId()).orElseThrow();
        Payment paymentAfterCancellation = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(stripeWebhookEventRepository.findAll())
                .anySatisfy(event -> {
                    assertThat(event.getStripeEventId()).isEqualTo(EVENT_ID);
                    assertThat(event.getEventType()).isEqualTo(EVENT_TYPE);
                });
        assertThat(orderAfterCancellation.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentAfterCancellation.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(persistedStock(product))
                .as("a terminally canceled checkout must release its durable inventory reservation")
                .isEqualTo(5);

        deliverCanceledWebhook(canceledEvent);
        deliverCanceledWebhook(canceledEvent(order, "evt_terminal_inventory_cancellation_distinct"));

        assertThat(persistedStock(product))
                .as("duplicate and distinct cancellation events must not restore an already-canceled order twice")
                .isEqualTo(5);
    }

    private Event canceledEvent(Order order) {
        return canceledEvent(order, EVENT_ID);
    }

    private Event canceledEvent(Order order, String eventId) {
        Event event = mock(Event.class);
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn(EVENT_TYPE);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(paymentIntent));
        when(paymentIntent.getId()).thenReturn(PAYMENT_INTENT_ID);
        when(paymentIntent.getMetadata()).thenReturn(Map.of("orderId", order.getId().toString()));
        return event;
    }

    private void deliverCanceledWebhook(Event event) throws Exception {
        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_placeholder"))
                    .thenReturn(event);
            mockMvc.perform(post(WEBHOOK_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Stripe-Signature", "sig")
                    .content("payload"))
                    .andExpect(status().isOk());
        }
    }

    private int persistedStock(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStock();
    }
}
