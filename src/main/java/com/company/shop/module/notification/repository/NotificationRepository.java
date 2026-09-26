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
              AND (next_attempt_at IS NULL OR next_attempt_at <= statement_timestamp())
            """, nativeQuery = true)
    long countDuePending();

    @Query(value = """
            SELECT COUNT(*)
            FROM notifications
            WHERE status = 'PENDING'
              AND next_attempt_at > statement_timestamp()
            """, nativeQuery = true)
    long countScheduledPending();

    @Query(value = """
            SELECT COUNT(*) FROM notifications
            WHERE (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= statement_timestamp()))
               OR (status = 'PROCESSING' AND claim_expires_at <= statement_timestamp())
            """, nativeQuery = true)
    long countActionable();

    @Query(value = """
            SELECT MIN(CASE WHEN status = 'PROCESSING' THEN claim_expires_at
                            ELSE COALESCE(next_attempt_at, created_at) END)
            FROM notifications
            WHERE (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= statement_timestamp()))
               OR (status = 'PROCESSING' AND claim_expires_at <= statement_timestamp())
            """, nativeQuery = true)
    Optional<Instant> findOldestActionableAt();

    @Query(value = """
            SELECT COALESCE(EXTRACT(EPOCH FROM GREATEST(
                statement_timestamp() - MIN(CASE WHEN status = 'PROCESSING' THEN claim_expires_at
                    ELSE COALESCE(next_attempt_at, created_at) END), INTERVAL '0 seconds')), 0)
            FROM notifications
            WHERE (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= statement_timestamp()))
               OR (status = 'PROCESSING' AND claim_expires_at <= statement_timestamp())
            """, nativeQuery = true)
    double findOldestActionableAgeSeconds();

    @Query("select min(n.lastAttemptAt) from Notification n where n.status = 'FAILED'")
    Optional<Instant> findOldestFailedLastAttemptAt();

    @Query(value = """
            SELECT COALESCE(EXTRACT(EPOCH FROM GREATEST(
                statement_timestamp() - MIN(last_attempt_at), INTERVAL '0 seconds')), 0)
            FROM notifications WHERE status = 'FAILED'
            """, nativeQuery = true)
    double findOldestFailedLastAttemptAgeSeconds();

    @Query(value = """
            SELECT *
            FROM notifications
            WHERE ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= statement_timestamp()))
                   OR (status = 'PROCESSING' AND claim_expires_at <= statement_timestamp()))
              AND attempts < :maxAttempts
            ORDER BY
              COALESCE(next_attempt_at, created_at) ASC,
              created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findClaimableBatchForUpdate(@Param("batchSize") int batchSize,
            @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = """
            UPDATE notifications SET status = 'FAILED', claim_token = NULL, claim_expires_at = NULL,
              last_error = 'Delivery claim expired after maximum attempts'
            WHERE status = 'PROCESSING' AND claim_expires_at <= statement_timestamp() AND attempts >= :maxAttempts
            """, nativeQuery = true)
    int failExhaustedExpiredClaims(@Param("maxAttempts") int maxAttempts);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant currentDatabaseTime();
}
