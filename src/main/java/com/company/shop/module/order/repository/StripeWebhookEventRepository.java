package com.company.shop.module.order.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.order.entity.StripeWebhookEvent;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO stripe_webhook_events (id, stripe_event_id, event_type, processed_at)
            VALUES (:id, :stripeEventId, :eventType, clock_timestamp() AT TIME ZONE 'UTC')
            ON CONFLICT (stripe_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(
            @Param("id") UUID id,
            @Param("stripeEventId") String stripeEventId,
            @Param("eventType") String eventType);
}
