/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.common.exception.BusinessException;
import com.company.shop.module.cart.service.CartService;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.entity.StripeWebhookEvent;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.exception.PaymentAlreadyCompletedException;
import com.company.shop.module.order.exception.PaymentProcessingException;
import com.company.shop.module.order.exception.PaymentRecordNotFoundException;
import com.company.shop.module.order.exception.StripeConfigurationException;
import com.company.shop.module.order.exception.WebhookProcessingException;
import com.company.shop.module.order.exception.WebhookSignatureInvalidException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.repository.StripeWebhookEventRepository;
import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;

import jakarta.annotation.PostConstruct;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final String STRIPE_EVENT_ID_UNIQUE_CONSTRAINT = "uq_stripe_webhook_events_stripe_event_id";

    @Value("${stripe.api-key}")
    private String secretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${stripe.public-key}")
    private String publicKey;

    private final OrderRepository orderRepo;
    private final PaymentRepository paymentRepo;
    private final CartService cartService;
    private final StripeWebhookEventRepository stripeWebhookEventRepository;

    public PaymentServiceImpl(OrderRepository orderRepo, PaymentRepository paymentRepo, CartService cartService,
            StripeWebhookEventRepository stripeWebhookEventRepository) {
        this.orderRepo = orderRepo;
        this.paymentRepo = paymentRepo;
        this.cartService = cartService;
        this.stripeWebhookEventRepository = stripeWebhookEventRepository;
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
            Payment payment = paymentRepo.findByOrderIdForUpdate(order.getId())
                    .orElseThrow(() -> new PaymentRecordNotFoundException(order.getId()));

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                throw new PaymentAlreadyCompletedException(order.getId());
            }

            if (payment.getProviderPaymentId() != null && !payment.getProviderPaymentId().isBlank()
                    && payment.getClientSecret() != null && !payment.getClientSecret().isBlank()) {
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

            if (!registerWebhookEvent(eventId, eventType)) {
                log.info("Ignoring duplicate Stripe webhook eventId={} type={}", eventId, eventType);
                return;
            }

            if (!"payment_intent.succeeded".equals(eventType)) {
                return;
            }

            var deserializer = event.getDataObjectDeserializer();
            PaymentIntent intent = (PaymentIntent) deserializer.getObject().orElse(null);
            if (intent == null) {
                return;
            }

            String orderId = intent.getMetadata().get("orderId");
            if (orderId == null || orderId.isBlank()) {
                throw new WebhookSignatureInvalidException("Missing orderId metadata in Stripe webhook.");
            }

            UUID parsedOrderId = UUID.fromString(orderId);
            Order order = orderRepo.findByIdForUpdate(parsedOrderId)
                    .orElseThrow(() -> new OrderNotFoundException(parsedOrderId));

            if (order.getStatus() == OrderStatus.PAID) {
                log.info("Ignoring duplicate payment webhook for already paid orderId={}", order.getId());
                return;
            }

            validatePaymentIntentMatchesOrder(intent, order);

            order.markAsPaid();
            orderRepo.save(order);

            Payment payment = paymentRepo.findByOrderIdForUpdate(order.getId())
                    .orElseThrow(() -> new PaymentRecordNotFoundException(order.getId()));

            if (payment.getProviderPaymentId() != null && !payment.getProviderPaymentId().isBlank()
                    && !payment.getProviderPaymentId().equals(intent.getId())) {
                throw new WebhookSignatureInvalidException(
                        "Webhook paymentIntent id does not match stored provider payment id.");
            }

            payment.markAsCompleted();
            paymentRepo.save(payment);

            cartService.clearCartForUser(order.getUser().getId());
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

    private boolean registerWebhookEvent(String eventId, String eventType) {
        try {
            // Registered in the same transaction as business effects; if processing rolls back,
            // the webhook event record also rolls back and can be retried safely.
            StripeWebhookEvent webhookEvent = new StripeWebhookEvent(eventId, eventType, LocalDateTime.now());
            stripeWebhookEventRepository.saveAndFlush(webhookEvent);
            return true;
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateStripeEventConstraintViolation(ex)) {
                return false;
            }
            throw ex;
        }
    }

    private boolean isDuplicateStripeEventConstraintViolation(DataIntegrityViolationException ex) {
        String constraintName = extractConstraintName(ex);
        return STRIPE_EVENT_ID_UNIQUE_CONSTRAINT.equals(constraintName);
    }

    private String extractConstraintName(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof ConstraintViolationException constraintViolationException) {
                return constraintViolationException.getConstraintName();
            }
            cursor = cursor.getCause();
        }
        return null;
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
            throw new WebhookSignatureInvalidException(
                    "Stripe webhook payment amount does not match order total.");
        }

        String currency = intent.getCurrency();
        if (currency == null || !"pln".equals(currency.toLowerCase(Locale.ROOT))) {
            throw new WebhookSignatureInvalidException("Stripe webhook currency does not match expected currency.");
        }

        if (order.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new WebhookProcessingException("Order amount is invalid for payment reconciliation.");
        }
    }
}
