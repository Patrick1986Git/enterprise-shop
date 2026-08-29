package com.company.shop.module.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    Optional<Notification> findBySourceEventId(UUID sourceEventId);

    @Query(value = "SELECT * FROM notifications WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Notification> findByIdForUpdate(@Param("id") UUID id);

    long countByStatus(NotificationStatus status);

    long countByRequeueCountGreaterThan(int requeueCount);

    @Query("select coalesce(sum(n.requeueCount), 0) from Notification n")
    long sumRequeueCount();

    @Query(value = """
            SELECT COUNT(*)
            FROM notifications
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            """, nativeQuery = true)
    long countDuePending(@Param("now") Instant now);

    @Query(value = """
            SELECT COUNT(*)
            FROM notifications
            WHERE status = 'PENDING'
              AND next_attempt_at > :now
            """, nativeQuery = true)
    long countScheduledPending(@Param("now") Instant now);

    @Query(value = """
            SELECT *
            FROM notifications
            WHERE ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                   OR (status = 'PROCESSING' AND claim_expires_at <= :now))
              AND attempts < :maxAttempts
            ORDER BY
              COALESCE(next_attempt_at, created_at) ASC,
              created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findClaimableBatchForUpdate(@Param("batchSize") int batchSize,
            @Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = """
            UPDATE notifications SET status = 'FAILED', claim_token = NULL, claim_expires_at = NULL,
              last_error = 'Delivery claim expired after maximum attempts'
            WHERE status = 'PROCESSING' AND claim_expires_at <= :now AND attempts >= :maxAttempts
            """, nativeQuery = true)
    int failExhaustedExpiredClaims(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);
}
