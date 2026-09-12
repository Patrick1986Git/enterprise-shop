package com.company.shop.module.order.exception;

import java.util.UUID;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.entity.StripePaymentConflictReason;

public class StripePaymentConflictException extends WebhookProcessingException {
    private final UUID orderId;
    private final UUID paymentId;
    private final String providerPaymentId;
    private final OrderStatus orderStatus;
    private final PaymentStatus paymentStatus;
    private final String stripeEventId;
    private final String eventType;

    public StripePaymentConflictException(UUID orderId, UUID paymentId, String providerPaymentId,
            OrderStatus orderStatus, PaymentStatus paymentStatus, String stripeEventId, String eventType) {
        super("Succeeded provider payment conflicts with the terminal local payment state.");
        this.orderId = orderId; this.paymentId = paymentId; this.providerPaymentId = providerPaymentId;
        this.orderStatus = orderStatus; this.paymentStatus = paymentStatus;
        this.stripeEventId = stripeEventId; this.eventType = eventType;
    }
    public UUID getOrderId() { return orderId; }
    public UUID getPaymentId() { return paymentId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public OrderStatus getOrderStatus() { return orderStatus; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public StripePaymentConflictReason getReason() { return StripePaymentConflictReason.TERMINAL_STATE_CONTRADICTION; }
    public String getStripeEventId() { return stripeEventId; }
    public String getEventType() { return eventType; }
}
