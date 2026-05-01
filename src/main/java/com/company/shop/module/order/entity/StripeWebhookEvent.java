package com.company.shop.module.order.entity;

import java.time.LocalDateTime;

import com.company.shop.common.model.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "stripe_webhook_events")
public class StripeWebhookEvent extends BaseEntity {

    @Column(name = "stripe_event_id", nullable = false, unique = true, length = 255)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false, length = 255)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    protected StripeWebhookEvent() {
    }

    public StripeWebhookEvent(String stripeEventId, String eventType, LocalDateTime processedAt) {
        this.stripeEventId = stripeEventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    public String getStripeEventId() {
        return stripeEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}
