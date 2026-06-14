package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    long countByStatus(OutboxEventStatus status);

    @Query("select min(e.createdAt) from OutboxEvent e where e.status = :status")
    Optional<Instant> findOldestCreatedAtByStatus(@Param("status") OutboxEventStatus status);

    @Query("select max(e.createdAt) from OutboxEvent e where e.status = :status")
    Optional<Instant> findNewestCreatedAtByStatus(@Param("status") OutboxEventStatus status);

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingBatchForUpdate(@Param("batchSize") int batchSize);
}
