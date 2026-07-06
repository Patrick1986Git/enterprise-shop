package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID>, JpaSpecificationExecutor<OutboxEvent> {

    long countByStatus(OutboxEventStatus status);

    long countByRequeueCountGreaterThan(int requeueCount);

    long countByStatusAndCreatedAtLessThanEqual(OutboxEventStatus status, Instant threshold);

    long countByStatusAndLastAttemptAtLessThanEqual(OutboxEventStatus status, Instant threshold);

    long countByStatusAndAttemptsGreaterThanEqual(OutboxEventStatus status, int attempts);

    @Query("select coalesce(sum(e.requeueCount), 0) from OutboxEvent e")
    long sumRequeueCount();

    @Query("select min(e.createdAt) from OutboxEvent e where e.status = :status")
    Optional<Instant> findOldestCreatedAtByStatus(@Param("status") OutboxEventStatus status);

    @Query("select max(e.createdAt) from OutboxEvent e where e.status = :status")
    Optional<Instant> findNewestCreatedAtByStatus(@Param("status") OutboxEventStatus status);

    @Query("select max(e.lastAttemptAt) from OutboxEvent e")
    Optional<Instant> findNewestAttemptAt();

    @Query("select max(e.lastAttemptAt) from OutboxEvent e where e.status = :status")
    Optional<Instant> findNewestAttemptAtByStatus(@Param("status") OutboxEventStatus status);

    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
            ORDER BY next_attempt_at ASC NULLS FIRST, created_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingBatchForUpdate(@Param("batchSize") int batchSize);
}
