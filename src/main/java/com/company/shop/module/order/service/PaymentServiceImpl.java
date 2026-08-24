/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.module.order.service;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.micrometer.core.instrument.MeterRegistry;

import com.company.shop.common.exception.BusinessException;
import com.company.shop.module.order.dto.PaymentIntentResponseDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.exception.OrderPaymentNotAllowedException;
import com.company.shop.module.order.exception.PaymentAlreadyCompletedException;
import com.company.shop.module.order.exception.PaymentAmountInvalidException;
import com.company.shop.module.order.exception.PaymentProcessingException;
import com.company.shop.module.order.exception.PaymentRecordNotFoundException;
import com.company.shop.module.order.exception.StripeConfigurationException;
import com.company.shop.module.order.exception.WebhookProcessingException;
import com.company.shop.module.order.exception.WebhookSignatureInvalidException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.expiration.StripePaymentIntentGateway;
import com.stripe.Stripe;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;

import jakarta.annotation.PostConstruct;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);
    private static final String PAYMENT_INTENT_METRIC = "shop.payment_intent.total";
    private static final String WEBHOOK_METRIC = "shop.webhook.total";
    private static final String RESULT_TAG = "result";
    private static final String RESULT_RECEIVED = "received";
    private static final String RESULT_PROCESSED = "processed";
    private static final String RESULT_DUPLICATE = "duplicate";
    private static final String RESULT_IGNORED = "ignored";
    private static final String RESULT_FAILED = "failed";

    @Value("${stripe.api-key}")
    private String secretKey;

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    @Value("${stripe.public-key}")
    private String publicKey;

    private final OrderRepository orderRepo;
    private final PaymentRepository paymentRepo;
    private final StripeWebhookEventRegistrar stripeWebhookEventRegistrar;
    private final MeterRegistry meterRegistry;
    private final PaymentTerminalTransitionService terminalTransitions;
    private final PaymentInitializationTransactionService paymentInitialization;
    private final StripePaymentIntentGateway stripeGateway;

    public PaymentServiceImpl(OrderRepository orderRepo, PaymentRepository paymentRepo,
            StripeWebhookEventRegistrar stripeWebhookEventRegistrar,
            MeterRegistry meterRegistry, PaymentTerminalTransitionService terminalTransitions,
            PaymentInitializationTransactionService paymentInitialization,
            StripePaymentIntentGateway stripeGateway) {
        this.orderRepo = orderRepo;
        this.paymentRepo = paymentRepo;
        this.stripeWebhookEventRegistrar = stripeWebhookEventRegistrar;
        this.meterRegistry = meterRegistry;
        this.terminalTransitions = terminalTransitions;
        this.paymentInitialization = paymentInitialization;
        this.stripeGateway = stripeGateway;
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
    public PaymentIntentResponseDTO createPaymentIntent(Order order) {
        try {
            log.info("Payment intent initialization started for orderId={} userId={}", order.getId(),
                    order.getUserId());
            PaymentInitialization initialization = paymentInitialization.prepare(order);
            if (initialization.isAttached()) {
                log.info("Reusing existing payment intent for orderId={} paymentId={} providerPaymentId={} paymentStatus={}",
                        order.getId(), null, null, "attached");
                incrementPaymentIntentMetric("reused");
                return new PaymentIntentResponseDTO(initialization.existingClientSecret(), publicKey);
            }
            PaymentIntent intent = stripeGateway.create(order.getId(), initialization.amount(),
                    "order-payment-intent-" + order.getId());
            paymentInitialization.attach(order.getId(), intent.getId(), intent.getClientSecret());
            log.info("Payment intent created for orderId={} paymentId={} providerPaymentId={} paymentStatus={}",
                    order.getId(), null, intent.getId(), "attached");
            incrementPaymentIntentMetric("created");

            return new PaymentIntentResponseDTO(intent.getClientSecret(), publicKey);
        } catch (BusinessException ex) {
            incrementPaymentIntentMetric("failed");
            throw ex;
        } catch (Exception e) {
            incrementPaymentIntentMetric("failed");
            log.error("Stripe PaymentIntent creation failed for orderId={}", order.getId(), e);
            throw new PaymentProcessingException("Failed to initialize payment for order: " + order.getId());
        }
    }

    private void incrementPaymentIntentMetric(String result) {
        meterRegistry.counter(PAYMENT_INTENT_METRIC, RESULT_TAG, result).increment();
    }

    private void incrementWebhookMetric(String result) {
        meterRegistry.counter(WEBHOOK_METRIC, RESULT_TAG, result).increment();
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
            incrementWebhookMetric(RESULT_RECEIVED);

            if (!stripeWebhookEventRegistrar.register(eventId, eventType)) {
                incrementWebhookMetric(RESULT_DUPLICATE);
                log.info("Ignoring duplicate Stripe webhook stripeEventId={} stripeEventType={}", eventId, eventType);
                return;
            }

            if ("payment_intent.succeeded".equals(eventType)) {
                incrementWebhookHandledMetric(handlePaymentIntentSucceeded(event));
                return;
            }

            if ("payment_intent.payment_failed".equals(eventType)) {
                incrementWebhookHandledMetric(handlePaymentIntentFailed(event));
                return;
            }
            if ("payment_intent.canceled".equals(eventType)) {
                incrementWebhookHandledMetric(handlePaymentIntentCanceled(event));
                return;
            }
            incrementWebhookMetric(RESULT_IGNORED);
            log.warn("Unhandled Stripe webhook event type stripeEventId={} stripeEventType={}", eventId, eventType);
        } catch (com.stripe.exception.SignatureVerificationException | IllegalArgumentException ex) {
            incrementWebhookMetric(RESULT_FAILED);
            log.warn("Invalid Stripe webhook payload/signature", ex);
            throw new WebhookSignatureInvalidException();
        } catch (OrderNotFoundException | PaymentAmountInvalidException | WebhookSignatureInvalidException ex) {
            incrementWebhookMetric(RESULT_FAILED);
            throw ex;
        } catch (Exception e) {
            incrementWebhookMetric(RESULT_FAILED);
            log.error("Stripe webhook processing failed", e);
            throw new WebhookProcessingException("Unable to process Stripe webhook event.");
        }
    }

    private void incrementWebhookHandledMetric(boolean handled) {
        incrementWebhookMetric(handled ? RESULT_PROCESSED : RESULT_IGNORED);
    }

    private boolean handlePaymentIntentSucceeded(com.stripe.model.Event event) {
        var deserializer = event.getDataObjectDeserializer();
        PaymentIntent intent = (PaymentIntent) deserializer.getObject().orElse(null);
        if (intent == null) {
            log.warn("Stripe webhook payload could not be deserialized to PaymentIntent stripeEventId={} stripeEventType={}",
                    event.getId(), event.getType());
            return false;
        }

        return terminalTransitions.convergeSucceeded(orderIdFromMetadata(intent), intent);
    }

    private boolean handlePaymentIntentFailed(com.stripe.model.Event event) {
        var deserializer = event.getDataObjectDeserializer();
        PaymentIntent intent = (PaymentIntent) deserializer.getObject().orElse(null);
        if (intent == null) {
            log.warn("Stripe webhook payload could not be deserialized to PaymentIntent stripeEventId={} stripeEventType={}",
                    event.getId(), event.getType());
            return false;
        }

        Order order = findOrderByWebhookMetadata(intent);
        Payment payment = paymentRepo.findByOrderIdForUpdate(order.getId())
                .orElseThrow(() -> new PaymentRecordNotFoundException(order.getId()));

        validateProviderPaymentId(intent, payment);

        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            log.info("Ignoring payment_failed webhook for already completed payment orderId={}", order.getId());
            return false;
        }

        payment.markAsFailed();
        paymentRepo.save(payment);
        log.info("Payment marked as failed from webhook orderId={} paymentId={} userId={} providerPaymentId={} orderStatus={} paymentStatus={}",
                order.getId(), payment.getId(), order.getUserId(), intent.getId(), order.getStatus(),
                payment.getStatus());
        return true;
    }

    private boolean handlePaymentIntentCanceled(com.stripe.model.Event event) {
        var deserializer = event.getDataObjectDeserializer();
        PaymentIntent intent = (PaymentIntent) deserializer.getObject().orElse(null);
        if (intent == null) {
            log.warn("Stripe webhook payload could not be deserialized to PaymentIntent stripeEventId={} stripeEventType={}",
                    event.getId(), event.getType());
            return false;
        }

        return terminalTransitions.convergeCanceled(orderIdFromMetadata(intent), intent) > 0;
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

    private UUID orderIdFromMetadata(PaymentIntent intent) {
        Map<String, String> metadata = intent.getMetadata();
        String orderId = metadata != null ? metadata.get("orderId") : null;
        if (orderId == null || orderId.isBlank()) throw new WebhookSignatureInvalidException("Missing orderId metadata in Stripe webhook.");
        return UUID.fromString(orderId);
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

}
