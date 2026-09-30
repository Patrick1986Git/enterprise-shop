package com.company.shop.module.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.company.shop.module.order.repository.StripeWebhookEventRepository;

@ExtendWith(MockitoExtension.class)
class StripeWebhookEventRegistrarTest {

    @Mock
    private StripeWebhookEventRepository stripeWebhookEventRepository;

    private StripeWebhookEventRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new StripeWebhookEventRegistrar(stripeWebhookEventRepository);
    }

    @Test
    void register_shouldReturnTrueWhenInsertReturnsOne() {
        when(stripeWebhookEventRepository.insertIgnoreDuplicate(any(), any(), any())).thenReturn(1);

        boolean result = registrar.register("evt_ok", "payment_intent.succeeded");

        assertThat(result).isTrue();
        verify(stripeWebhookEventRepository).insertIgnoreDuplicate(any(UUID.class), eq("evt_ok"),
                eq("payment_intent.succeeded"));
    }

    @Test
    void register_shouldReturnFalseWhenInsertReturnsZero() {
        when(stripeWebhookEventRepository.insertIgnoreDuplicate(any(), any(), any())).thenReturn(0);

        boolean result = registrar.register("evt_duplicate", "payment_intent.succeeded");

        assertThat(result).isFalse();
        verify(stripeWebhookEventRepository).insertIgnoreDuplicate(any(UUID.class), eq("evt_duplicate"),
                eq("payment_intent.succeeded"));
    }

    @Test
    void register_shouldReturnFalseWhenInsertReturnsUnexpectedValue() {
        when(stripeWebhookEventRepository.insertIgnoreDuplicate(any(), any(), any())).thenReturn(2);

        boolean result = registrar.register("evt_unexpected", "payment_intent.succeeded");

        assertThat(result).isFalse();
    }
}
