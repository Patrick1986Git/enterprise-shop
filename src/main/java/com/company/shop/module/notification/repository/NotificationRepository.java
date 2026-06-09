package com.company.shop.module.notification.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

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
