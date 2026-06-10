package com.company.shop.module.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findBySourceEventId(UUID sourceEventId);

    Page<Notification> findByStatus(NotificationStatus status, Pageable pageable);

    Page<Notification> findByType(String type, Pageable pageable);

    Page<Notification> findByRecipientContainingIgnoreCase(String recipient, Pageable pageable);

    @Query("""
            SELECT n
            FROM Notification n
            WHERE (:status IS NULL OR n.status = :status)
              AND (:type IS NULL OR n.type = :type)
              AND (:recipient IS NULL OR LOWER(n.recipient) LIKE LOWER(CONCAT(CONCAT('%', :recipient), '%')))
            """)
    Page<Notification> findAllForAdmin(
            @Param("status") NotificationStatus status,
            @Param("type") String type,
            @Param("recipient") String recipient,
            Pageable pageable);

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
