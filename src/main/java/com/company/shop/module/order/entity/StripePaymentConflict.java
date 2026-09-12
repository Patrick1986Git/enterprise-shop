package com.company.shop.module.order.entity;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "stripe_payment_conflicts")
public class StripePaymentConflict extends BaseEntity {
    @Column(name = "order_id", nullable = false, updatable = false) private UUID orderId;
    @Column(name = "payment_id", nullable = false, updatable = false) private UUID paymentId;
    @Column(name = "stripe_event_id", nullable = false, updatable = false, unique = true, length = 255) private String stripeEventId;
    @Column(name = "provider_payment_id", nullable = false, updatable = false, length = 255) private String providerPaymentId;
    @Column(name = "event_type", nullable = false, updatable = false, length = 100) private String eventType;
    @Column(name = "order_status", nullable = false, updatable = false, length = 32) @Enumerated(EnumType.STRING) private OrderStatus orderStatus;
    @Column(name = "payment_status", nullable = false, updatable = false, length = 32) @Enumerated(EnumType.STRING) private PaymentStatus paymentStatus;
    @Column(name = "reason", nullable = false, updatable = false, length = 64) @Enumerated(EnumType.STRING) private StripePaymentConflictReason reason;
    @Column(name = "observed_at", nullable = false, updatable = false) private Instant observedAt;

    protected StripePaymentConflict() {}
    public StripePaymentConflict(UUID orderId, UUID paymentId, String stripeEventId, String providerPaymentId,
            String eventType, OrderStatus orderStatus, PaymentStatus paymentStatus,
            StripePaymentConflictReason reason, Instant observedAt) {
        this.orderId = orderId; this.paymentId = paymentId; this.stripeEventId = stripeEventId;
        this.providerPaymentId = providerPaymentId; this.eventType = eventType; this.orderStatus = orderStatus;
        this.paymentStatus = paymentStatus; this.reason = reason; this.observedAt = observedAt;
    }
    public UUID getOrderId() { return orderId; }
    public UUID getPaymentId() { return paymentId; }
    public String getStripeEventId() { return stripeEventId; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public String getEventType() { return eventType; }
    public OrderStatus getOrderStatus() { return orderStatus; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public StripePaymentConflictReason getReason() { return reason; }
    public Instant getObservedAt() { return observedAt; }
}
