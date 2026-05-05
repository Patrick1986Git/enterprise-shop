/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.common.exception.BusinessException;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.exception.PaymentAlreadyCompletedException;
import com.company.shop.module.order.exception.PaymentProcessingException;
import com.company.shop.module.order.exception.PaymentRecordNotFoundException;
import com.company.shop.module.order.exception.StripeConfigurationException;
import com.company.shop.module.order.exception.WebhookProcessingException;
import com.company.shop.module.order.exception.WebhookSignatureInvalidException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;

import jakarta.annotation.PostConstruct;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    @Value("${stripe.api-key}")
    private String secretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${stripe.public-key}")
    private String publicKey;

    private final OrderRepository orderRepo;
    private final PaymentRepository paymentRepo;
    private final CartService cartService;
    private final StripeWebhookEventRegistrar stripeWebhookEventRegistrar;

    public PaymentServiceImpl(OrderRepository orderRepo, PaymentRepository paymentRepo, CartService cartService,
            StripeWebhookEventRegistrar stripeWebhookEventRegistrar) {
        this.orderRepo = orderRepo;
        this.paymentRepo = paymentRepo;
        this.cartService = cartService;
        this.stripeWebhookEventRegistrar = stripeWebhookEventRegistrar;
    }

    @PostConstruct
    public void init() {
        if (secretKey == null || secretKey.isBlank()) {
            log.error("Stripe API key is missing in configuration.");
            throw new StripeConfigurationException("Stripe API key is missing in configuration.");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Stripe webhook secret is missing in configuration.");
            throw new StripeConfigurationException("Stripe webhook secret is missing in configuration.");
        }
        if (publicKey == null || publicKey.isBlank()) {
            log.error("Stripe public key is missing in configuration.");
            throw new StripeConfigurationException("Stripe public key is missing in configuration.");
        }
        Stripe.apiKey = secretKey;
    }

    @Override
    @Transactional
    public PaymentIntentResponseDTO createPaymentIntent(Order order) {
        try {
            log.info("Payment intent initialization started for orderId={} userId={}", order.getId(),
                    order.getUser().getId());
            Payment payment = paymentRepo.findByOrderIdForUpdate(order.getId())
                    .orElseThrow(() -> new PaymentRecordNotFoundException(order.getId()));

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                throw new PaymentAlreadyCompletedException(order.getId());
            }

            if (payment.getProviderPaymentId() != null && !payment.getProviderPaymentId().isBlank()
                    && payment.getClientSecret() != null && !payment.getClientSecret().isBlank()) {
                log.info("Reusing existing payment intent for orderId={} paymentId={} providerPaymentId={} paymentStatus={}",
                        order.getId(), payment.getId(), payment.getProviderPaymentId(), payment.getStatus());
                return new PaymentIntentResponseDTO(payment.getClientSecret(), publicKey);
            }

            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(order.getTotalAmount().movePointRight(2).longValue())
                    .setCurrency("pln")
                    .putMetadata("orderId", order.getId().toString())
                    .build();

            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey("order-payment-intent-" + order.getId())
                    .build();

            PaymentIntent intent = PaymentIntent.create(params, requestOptions);
            payment.attachProviderPayment(intent.getId(), intent.getClientSecret());
            paymentRepo.save(payment);
            log.info("Payment intent created for orderId={} paymentId={} providerPaymentId={} paymentStatus={}",
                    order.getId(), payment.getId(), intent.getId(), payment.getStatus());

            return new PaymentIntentResponseDTO(intent.getClientSecret(), publicKey);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Stripe PaymentIntent creation failed for orderId={}", order.getId(), e);
            throw new PaymentProcessingException("Failed to initialize payment for order: " + order.getId());
        }
    }

    @Override
    @Transactional
    public void handleWebhook(String payload, String sigHeader) {
        try {
            var event = Webhook.constructEvent(payload, sigHeader, webhookSecret);

            String eventId = event.getId();
            if (eventId == null || eventId.isBlank()) {
                throw new WebhookSignatureInvalidException("Missing Stripe event id in webhook payload.");
            }

            String eventType = event.getType();
            if (eventType == null || eventType.isBlank()) {
                throw new WebhookSignatureInvalidException("Missing Stripe event type in webhook payload.");
            }
            log.info("Stripe webhook received stripeEventId={} stripeEventType={}", eventId, eventType);

            if (!stripeWebhookEventRegistrar.register(eventId, eventType)) {
                log.info("Ignoring duplicate Stripe webhook stripeEventId={} stripeEventType={}", eventId, eventType);
                return;
            }

            if ("payment_intent.succeeded".equals(eventType)) {
                handlePaymentIntentSucceeded(event);
                return;
            }

            if ("payment_intent.payment_failed".equals(eventType)) {
                handlePaymentIntentFailed(event);
                return;
            }
            log.warn("Unhandled Stripe webhook event type stripeEventId={} stripeEventType={}", eventId, eventType);
        } catch (com.stripe.exception.SignatureVerificationException | IllegalArgumentException ex) {
            log.warn("Invalid Stripe webhook payload/signature", ex);
            throw new WebhookSignatureInvalidException();
        } catch (OrderNotFoundException | WebhookSignatureInvalidException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Stripe webhook processing failed", e);
            throw new WebhookProcessingException("Unable to process Stripe webhook event.");
        }
    }

    private void handlePaymentIntentSucceeded(com.stripe.model.Event event) {
        var deserializer = event.getDataObjectDeserializer();
        PaymentIntent intent = (PaymentIntent) deserializer.getObject().orElse(null);
        if (intent == null) {
            log.warn("Stripe webhook payload could not be deserialized to PaymentIntent stripeEventId={} stripeEventType={}",
                    event.getId(), event.getType());
            return;
        }

        Order order = findOrderByWebhookMetadata(intent);
        if (order.getStatus() == OrderStatus.PAID) {
            log.info("Ignoring duplicate payment webhook for already paid orderId={}", order.getId());
            return;
        }

        validatePaymentIntentMatchesOrder(intent, order);

        Payment payment = paymentRepo.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new PaymentRecordNotFoundException(order.getId()));

        validateProviderPaymentId(intent, payment);

        order.markAsPaid();
        orderRepo.save(order);

        payment.markAsCompleted();
        paymentRepo.save(payment);
        log.info("Order marked as paid orderId={} paymentId={} userId={} providerPaymentId={} orderStatus={} paymentStatus={}",
                order.getId(), payment.getId(), order.getUser().getId(), intent.getId(), order.getStatus(),
                payment.getStatus());

        cartService.clearCartForUser(order.getUser().getId());
    }

    private void handlePaymentIntentFailed(com.stripe.model.Event event) {
        var deserializer = event.getDataObjectDeserializer();
        PaymentIntent intent = (PaymentIntent) deserializer.getObject().orElse(null);
        if (intent == null) {
            log.warn("Stripe webhook payload could not be deserialized to PaymentIntent stripeEventId={} stripeEventType={}",
                    event.getId(), event.getType());
            return;
        }

        Order order = findOrderByWebhookMetadata(intent);
        Payment payment = paymentRepo.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new PaymentRecordNotFoundException(order.getId()));

        validateProviderPaymentId(intent, payment);

        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            log.info("Ignoring payment_failed webhook for already completed payment orderId={}", order.getId());
            return;
        }

        payment.markAsFailed();
        paymentRepo.save(payment);
        log.info("Payment marked as failed from webhook orderId={} paymentId={} userId={} providerPaymentId={} orderStatus={} paymentStatus={}",
                order.getId(), payment.getId(), order.getUser().getId(), intent.getId(), order.getStatus(),
                payment.getStatus());
    }

    private Order findOrderByWebhookMetadata(PaymentIntent intent) {
        Map<String, String> metadata = intent.getMetadata();
        String orderId = metadata != null ? metadata.get("orderId") : null;
        if (orderId == null || orderId.isBlank()) {
            log.warn("Stripe webhook missing orderId metadata providerPaymentId={}", intent.getId());
            throw new WebhookSignatureInvalidException("Missing orderId metadata in Stripe webhook.");
        }

        UUID parsedOrderId = UUID.fromString(orderId);
        return orderRepo.findByIdForUpdate(parsedOrderId)
                .orElseThrow(() -> new OrderNotFoundException(parsedOrderId));
    }

    private void validateProviderPaymentId(PaymentIntent intent, Payment payment) {
        if (payment.getProviderPaymentId() != null && !payment.getProviderPaymentId().isBlank()
                && !payment.getProviderPaymentId().equals(intent.getId())) {
            log.warn("Stripe providerPaymentId mismatch orderId={} paymentId={} storedProviderPaymentId={} incomingProviderPaymentId={}",
                    payment.getOrder().getId(), payment.getId(), payment.getProviderPaymentId(), intent.getId());
            throw new WebhookSignatureInvalidException(
                    "Webhook paymentIntent id does not match stored provider payment id.");
        }
    }

    private void validatePaymentIntentMatchesOrder(PaymentIntent intent, Order order) {
        Long amount = intent.getAmountReceived() != null && intent.getAmountReceived() > 0
                ? intent.getAmountReceived()
                : intent.getAmount();

        if (amount == null) {
            throw new WebhookSignatureInvalidException("Stripe webhook does not contain payment amount.");
        }

        long expectedAmount = order.getTotalAmount().movePointRight(2).longValue();
        if (amount.longValue() != expectedAmount) {
            log.warn("Stripe amount mismatch orderId={} providerPaymentId={} expectedAmount={} actualAmount={}",
                    order.getId(), intent.getId(), expectedAmount, amount);
            throw new WebhookSignatureInvalidException(
                    "Stripe webhook payment amount does not match order total.");
        }

        String currency = intent.getCurrency();
        if (currency == null || !"pln".equals(currency.toLowerCase(Locale.ROOT))) {
            log.warn("Stripe currency mismatch orderId={} providerPaymentId={} expectedCurrency=pln actualCurrency={}",
                    order.getId(), intent.getId(), currency);
            throw new WebhookSignatureInvalidException("Stripe webhook currency does not match expected currency.");
        }

        if (order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new WebhookProcessingException("Order amount is invalid for payment reconciliation.");
        }
    }
}
