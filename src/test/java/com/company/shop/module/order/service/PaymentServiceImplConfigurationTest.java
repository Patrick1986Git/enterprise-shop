package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.order.exception.StripeConfigurationException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.stripe.Stripe;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PaymentServiceImplConfigurationTest {

    private String previousStripeApiKey;

    @BeforeEach
    void rememberStripeApiKey() {
        previousStripeApiKey = Stripe.apiKey;
    }

    @AfterEach
    void restoreStripeApiKey() {
        Stripe.apiKey = previousStripeApiKey;
    }

    @Test
    void init_shouldThrowWhenApiKeyIsBlank() {
        PaymentServiceImpl service = serviceWithConfiguration(" ", "whsec_test", "pk_test");

        assertThatThrownBy(service::init)
                .isInstanceOf(StripeConfigurationException.class)
                .hasMessageContaining("API key");
    }

    @Test
    void init_shouldThrowWhenWebhookSecretIsMissing() {
        PaymentServiceImpl service = serviceWithConfiguration("sk_test", null, "pk_test");

        assertThatThrownBy(service::init)
                .isInstanceOf(StripeConfigurationException.class)
                .hasMessageContaining("webhook secret");
    }

    @Test
    void init_shouldThrowWhenPublicKeyIsBlank() {
        PaymentServiceImpl service = serviceWithConfiguration("sk_test", "whsec_test", " ");

        assertThatThrownBy(service::init)
                .isInstanceOf(StripeConfigurationException.class)
                .hasMessageContaining("public key");
    }

    @Test
    void init_shouldConfigureStripeApiKeyWhenConfigurationIsComplete() {
        PaymentServiceImpl service = serviceWithConfiguration("sk_test_expected", "whsec_test", "pk_test");

        service.init();

        assertThat(Stripe.apiKey).isEqualTo("sk_test_expected");
    }

    private PaymentServiceImpl serviceWithConfiguration(String apiKey, String webhookSecret, String publicKey) {
        OrderRepository orders = mock(OrderRepository.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        PaymentServiceImpl service = new PaymentServiceImpl(orders, payments,
                mock(StripeWebhookEventRegistrar.class), new SimpleMeterRegistry(),
                mock(PaymentTerminalTransitionService.class),
                new PaymentInitializationTransactionService(orders, payments),
                new com.company.shop.module.order.expiration.StripePaymentIntentGatewayImpl());
        setField(service, "secretKey", apiKey);
        setField(service, "webhookSecret", webhookSecret);
        setField(service, "publicKey", publicKey);
        return service;
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
