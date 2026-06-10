package com.company.shop.module.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    Optional<Notification> findBySourceEventId(UUID sourceEventId);

    long countByStatus(NotificationStatus status);

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
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
            ORDER BY
              COALESCE(next_attempt_at, created_at) ASC,
              created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findPendingBatchForUpdate(@Param("batchSize") int batchSize);
}
