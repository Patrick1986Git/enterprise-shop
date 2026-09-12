package com.company.shop.module.order.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import com.company.shop.module.order.entity.StripePaymentConflict;

public interface StripePaymentConflictRepository extends JpaRepository<StripePaymentConflict, UUID> {
    long countByStripeEventId(String stripeEventId);

    @Modifying
    @Query(value = """
            INSERT INTO stripe_payment_conflicts (id, order_id, payment_id, stripe_event_id, provider_payment_id,
                event_type, order_status, payment_status, reason, observed_at)
            VALUES (:id, :orderId, :paymentId, :eventId, :providerPaymentId, :eventType, :orderStatus,
                :paymentStatus, :reason, :observedAt)
            ON CONFLICT (stripe_event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoreDuplicate(@Param("id") UUID id, @Param("orderId") UUID orderId,
            @Param("paymentId") UUID paymentId, @Param("eventId") String eventId,
            @Param("providerPaymentId") String providerPaymentId, @Param("eventType") String eventType,
            @Param("orderStatus") String orderStatus, @Param("paymentStatus") String paymentStatus,
            @Param("reason") String reason, @Param("observedAt") Instant observedAt);
}
