package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import com.company.shop.common.model.BaseEntity;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderItem;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.exception.WebhookProcessingException;
import com.company.shop.module.order.exception.WebhookSignatureInvalidException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.user.entity.User;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplWebhookTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CartService cartService;

    @Mock
    private StripeWebhookEventRegistrar stripeWebhookEventRegistrar;

    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(orderRepository, paymentRepository, cartService, stripeWebhookEventRegistrar,
                new SimpleMeterRegistry());
        setField(service, "webhookSecret", "whsec_test_123");
    }

    @Test
    void handleWebhook_shouldIgnoreWhenEventTypeIsNotPaymentIntentSucceeded() {
        givenWebhookEventRegistrationSucceeds();
        Event event = event("evt_unsupported", "payment_intent.processing");

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verifyWebhookEventRegistered("evt_unsupported", "payment_intent.processing");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldIgnoreWhenDeserializedPaymentIntentIsMissing() {
        givenWebhookEventRegistrationSucceeds();
        Event event = event("evt_missing_intent", "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verifyWebhookEventRegistered("evt_missing_intent", "payment_intent.succeeded");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldIgnoreFailedEventWhenDeserializerObjectMissing() {
        givenWebhookEventRegistrationSucceeds();
        Event event = event("evt_failed_missing_intent", "payment_intent.payment_failed");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.empty());

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verifyWebhookEventRegistered("evt_failed_missing_intent", "payment_intent.payment_failed");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldRegisterSupportedEventBeforeBusinessValidationFailure() {
        givenWebhookEventRegistrationSucceeds();
        Event event = event("evt_missing_order_id", "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        PaymentIntent intent = mock(PaymentIntent.class);

        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));
        when(intent.getMetadata()).thenReturn(Map.of());

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("orderId");

            verifyWebhookEventRegistered("evt_missing_order_id", "payment_intent.succeeded");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldRegisterFailedEventBeforeBusinessValidationFailure() {
        givenWebhookEventRegistrationSucceeds();
        Event event = failedEvent("evt_failed_missing_order_id", paymentIntentWithEmptyMetadata());

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("orderId");

            verifyWebhookEventRegistered("evt_failed_missing_order_id", "payment_intent.payment_failed");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenOrderIdMetadataIsNotValidUuid() {
        givenWebhookEventRegistrationSucceeds();
        Event event = event("evt_invalid_uuid", "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        PaymentIntent intent = mock(PaymentIntent.class);

        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));
        when(intent.getMetadata()).thenReturn(Map.of("orderId", "not-a-uuid"));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class);

            verifyWebhookEventRegistered("evt_invalid_uuid", "payment_intent.succeeded");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenPaymentIntentMetadataIsNull() {
        givenWebhookEventRegistrationSucceeds();
        Event event = event("evt_null_metadata", "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        PaymentIntent intent = mock(PaymentIntent.class);

        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));
        when(intent.getMetadata()).thenReturn(null);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("orderId");

            verifyWebhookEventRegistered("evt_null_metadata", "payment_intent.succeeded");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenEventIdMissing() {
        Event event = eventWithIdOnly(" ");

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("event id");

            verifyNoInteractions(stripeWebhookEventRegistrar, orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenEventIdIsNull() {
        Event event = eventWithIdOnly(null);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("event id");

            verifyNoInteractions(stripeWebhookEventRegistrar, orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenEventTypeMissing() {
        Event event = event("evt_missing_type", " ");

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("event type");

            verifyNoInteractions(stripeWebhookEventRegistrar, orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenEventTypeIsNull() {
        Event event = event("evt_missing_type_null", null);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("event type");

            verifyNoInteractions(stripeWebhookEventRegistrar, orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenPayloadOrSignatureInvalid() {
        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123"))
                    .thenThrow(mock(SignatureVerificationException.class));

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class);

            verifyNoInteractions(stripeWebhookEventRegistrar, orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldIgnoreWhenWebhookEventAlreadyProcessed() {
        Event event = event("evt_duplicate", "payment_intent.succeeded");
        when(stripeWebhookEventRegistrar.register("evt_duplicate", "payment_intent.succeeded")).thenReturn(false);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verify(stripeWebhookEventRegistrar).register("evt_duplicate", "payment_intent.succeeded");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldIgnoreDuplicatePaymentFailedWebhookEvent() {
        Event event = event("evt_failed_duplicate", "payment_intent.payment_failed");
        when(stripeWebhookEventRegistrar.register("evt_failed_duplicate", "payment_intent.payment_failed"))
                .thenReturn(false);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verify(stripeWebhookEventRegistrar).register("evt_failed_duplicate", "payment_intent.payment_failed");
            verifyNoInteractions(orderRepository, paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldIgnoreDuplicateWhenOrderAlreadyPaid() {
        givenWebhookEventRegistrationSucceeds();
        Order paidOrder = orderWithTotal(BigDecimal.valueOf(25));
        paidOrder.markAsPaid();
        Event event = succeededEvent("evt_already_paid", paymentIntentWithMetadata(paidOrder.getId()));

        when(orderRepository.findByIdForUpdate(paidOrder.getId())).thenReturn(Optional.of(paidOrder));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verifyWebhookEventRegistered("evt_already_paid", "payment_intent.succeeded");
            verify(orderRepository).findByIdForUpdate(paidOrder.getId());
            verify(orderRepository, never()).save(paidOrder);
            verifyNoInteractions(paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenOrderNotFoundForWebhookOrderId() {
        givenWebhookEventRegistrationSucceeds();
        UUID orderId = UUID.randomUUID();
        Event event = succeededEvent("evt_order_not_found", paymentIntentWithMetadata(orderId));

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.empty());

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(OrderNotFoundException.class)
                    .hasMessageContaining(orderId.toString());

            verifyWebhookEventRegistered("evt_order_not_found", "payment_intent.succeeded");
            verify(orderRepository).findByIdForUpdate(orderId);
            verifyNoInteractions(paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenPaymentRecordMissing() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(19.99));
        Event event = succeededEvent("evt_payment_missing",
                paymentIntentWithMetadataAndAmountReceivedAndCurrency(order.getId(), 1999L, "pln"));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.empty());

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookProcessingException.class)
                    .hasMessageContaining("Unable to process Stripe webhook event.");

            verifyWebhookEventRegistered("evt_payment_missing", "payment_intent.succeeded");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
            verify(orderRepository, never()).save(order);
            verify(paymentRepository).findByOrderIdForUpdate(order.getId());
            verify(paymentRepository, never()).save(any(Payment.class));
            verify(cartService, never()).clearCartForUser(order.getUser().getId());
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenStoredProviderPaymentIdDiffersFromWebhookIntentId() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(25));
        Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
        payment.attachProviderPayment("pi_other", "cs_any");

        Event event = succeededEvent("evt_provider_mismatch",
                paymentIntentWithMetadataAndAmountReceivedCurrencyAndId(order.getId(), 2500L, "pln", "pi_actual"));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("does not match");

            verifyWebhookEventRegistered("evt_provider_mismatch", "payment_intent.succeeded");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
            verify(orderRepository, never()).save(order);
            verify(paymentRepository).findByOrderIdForUpdate(order.getId());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentRepository, never()).save(payment);
            verify(cartService, never()).clearCartForUser(order.getUser().getId());
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenFailedEventProviderPaymentIdDiffers() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(25));
        Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
        payment.attachProviderPayment("pi_existing", "cs_any");

        Event event = failedEvent("evt_failed_provider_mismatch",
                paymentIntentWithMetadataAndId(order.getId(), "pi_other"));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("does not match");

            verifyWebhookEventRegistered("evt_failed_provider_mismatch", "payment_intent.payment_failed");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            verify(paymentRepository, never()).save(payment);
            verify(orderRepository, never()).save(order);
            verifyNoInteractions(cartService);
        }
    }

    @Test
    void handleWebhook_shouldMarkPaymentFailedWhenPaymentIntentFailedEventValid() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(19.99));
        Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
        Event event = failedEvent("evt_payment_failed", paymentIntentWithMetadata(order.getId()));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verifyWebhookEventRegistered("evt_payment_failed", "payment_intent.payment_failed");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
            verify(paymentRepository).save(payment);
            verify(orderRepository, never()).save(order);
            verifyNoInteractions(cartService);
        }
    }

    @Test
    void handleWebhook_shouldNotMarkCompletedPaymentAsFailedWhenPaymentIntentFailedArrivesLate() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(19.99));
        Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
        payment.markAsCompleted();
        Event event = failedEvent("evt_payment_failed_late", paymentIntentWithMetadata(order.getId()));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verifyWebhookEventRegistered("evt_payment_failed_late", "payment_intent.payment_failed");
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
            verify(paymentRepository, never()).save(payment);
            verify(orderRepository, never()).save(order);
            verifyNoInteractions(cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenAmountDoesNotMatchOrderTotal() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(19.99));
        Event event = succeededEvent("evt_amount_mismatch",
                paymentIntentWithMetadataAndAmountReceived(order.getId(), 1500L));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("amount does not match");

            verifyWebhookEventRegistered("evt_amount_mismatch", "payment_intent.succeeded");
            verify(orderRepository, never()).save(order);
            verifyNoInteractions(paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldThrowWhenCurrencyIsNotPln() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(19.99));
        Event event = succeededEvent("evt_currency_mismatch",
                paymentIntentWithMetadataAndAmountReceivedAndCurrency(order.getId(), 1999L, "eur"));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookSignatureInvalidException.class)
                    .hasMessageContaining("currency does not match");

            verifyWebhookEventRegistered("evt_currency_mismatch", "payment_intent.succeeded");
            verify(orderRepository, never()).save(order);
            verifyNoInteractions(paymentRepository, cartService);
        }
    }

    @Test
    void handleWebhook_shouldWrapUnexpectedExceptionIntoWebhookProcessingException() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(19.99));
        Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
        payment.attachProviderPayment("pi_123", "cs_123");
        Event event = succeededEvent("evt_unexpected",
                paymentIntentWithMetadataAndAmountReceivedCurrencyAndId(order.getId(), 1999L, "pln", "pi_123"));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenThrow(new RuntimeException("db write failed"));

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            assertThatThrownBy(() -> service.handleWebhook("payload", "sig"))
                    .isInstanceOf(WebhookProcessingException.class)
                    .hasMessageContaining("Unable to process Stripe webhook event.");

            verifyWebhookEventRegistered("evt_unexpected", "payment_intent.succeeded");
            verify(orderRepository).save(order);
            verify(paymentRepository).save(payment);
            verify(cartService, never()).clearCartForUser(order.getUser().getId());
        }
    }

    @Test
    void handleWebhook_shouldMarkOrderPaidPaymentCompletedAndClearCartWhenSucceededEventValid() {
        givenWebhookEventRegistrationSucceeds();
        Order order = orderWithTotal(BigDecimal.valueOf(19.99));
        Payment payment = new Payment(order, "STRIPE", order.getTotalAmount());
        payment.attachProviderPayment("pi_123", "cs_123");
        Event event = succeededEvent("evt_success",
                paymentIntentWithMetadataAndAmountReceivedCurrencyAndId(order.getId(), 1999L, "PLN", "pi_123"));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(paymentRepository.findByOrderIdForUpdate(order.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.save(payment)).thenReturn(payment);

        try (MockedStatic<Webhook> webhookStatic = mockStatic(Webhook.class)) {
            webhookStatic.when(() -> Webhook.constructEvent("payload", "sig", "whsec_test_123")).thenReturn(event);

            service.handleWebhook("payload", "sig");

            verifyWebhookEventRegistered("evt_success", "payment_intent.succeeded");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            verify(orderRepository).save(order);
            verify(paymentRepository).save(payment);
            verify(cartService).clearCartForUser(order.getUser().getId());
        }
    }

    private void givenWebhookEventRegistrationSucceeds() {
        when(stripeWebhookEventRegistrar.register(any(), any())).thenReturn(true);
    }

    private void verifyWebhookEventRegistered(String expectedEventId, String expectedEventType) {
        verify(stripeWebhookEventRegistrar).register(expectedEventId, expectedEventType);
    }

    private Event event(String eventId, String eventType) {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn(eventType);
        return event;
    }

    private Event eventWithIdOnly(String eventId) {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        return event;
    }

    private Event succeededEvent(String eventId, PaymentIntent intent) {
        Event event = event(eventId, "payment_intent.succeeded");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.of(intent));
        return event;
    }

    private Event failedEvent(String eventId, PaymentIntent intent) {
        Event event = event(eventId, "payment_intent.payment_failed");
        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(deserializer.getObject()).thenReturn(Optional.ofNullable(intent));
        return event;
    }

    private PaymentIntent paymentIntentWithMetadata(UUID orderId) {
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getMetadata()).thenReturn(Map.of("orderId", orderId.toString()));
        return intent;
    }

    private PaymentIntent paymentIntentWithEmptyMetadata() {
        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getMetadata()).thenReturn(Map.of());
        return intent;
    }

    private PaymentIntent paymentIntentWithMetadataAndId(UUID orderId, String intentId) {
        PaymentIntent intent = paymentIntentWithMetadata(orderId);
        when(intent.getId()).thenReturn(intentId);
        return intent;
    }

    private PaymentIntent paymentIntentWithMetadataAndAmountReceived(UUID orderId, Long amountReceived) {
        PaymentIntent intent = paymentIntentWithMetadata(orderId);
        when(intent.getAmountReceived()).thenReturn(amountReceived);
        return intent;
    }

    private PaymentIntent paymentIntentWithMetadataAndAmountReceivedAndCurrency(UUID orderId, Long amountReceived,
            String currency) {
        PaymentIntent intent = paymentIntentWithMetadataAndAmountReceived(orderId, amountReceived);
        when(intent.getCurrency()).thenReturn(currency);
        return intent;
    }

    private PaymentIntent paymentIntentWithMetadataAndAmountReceivedCurrencyAndId(UUID orderId, Long amountReceived,
            String currency, String intentId) {
        PaymentIntent intent = paymentIntentWithMetadataAndAmountReceivedAndCurrency(orderId, amountReceived, currency);
        when(intent.getId()).thenReturn(intentId);
        return intent;
    }

    private Order orderWithTotal(BigDecimal unitPrice) {
        User user = new User("john@example.com", "encoded", "John", "Doe");
        setEntityId(user, UUID.randomUUID());

        Category category = new Category("Accessories", "accessories", "desc");
        Product product = new Product("Cable", "cable", "SKU-1", "desc", unitPrice, 10, category);
        setEntityId(product, UUID.randomUUID());

        Order order = new Order(user);
        setEntityId(order, UUID.randomUUID());
        order.addItem(new OrderItem(product, 1, unitPrice));
        return order;
    }

    private void setEntityId(Object entity, UUID id) {
        try {
            Field field = BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
