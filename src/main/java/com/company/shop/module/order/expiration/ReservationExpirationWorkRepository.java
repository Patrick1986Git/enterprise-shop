package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationExpirationWorkRepository extends JpaRepository<ReservationExpirationWork, UUID> {
    @Query(value = """
            SELECT w.id FROM reservation_expiration_work w JOIN orders o ON o.id = w.order_id
            WHERE o.status = 'NEW' AND o.deleted = false
              AND ((w.status = 'PENDING' AND w.next_attempt_at <= :now)
                OR (w.status = 'CLAIMED' AND w.claim_until <= :now))
            ORDER BY w.next_attempt_at, w.id LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findDueCandidateIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM reservation_expiration_work
            WHERE id = :id AND ((status = 'PENDING' AND next_attempt_at <= :now)
              OR (status = 'CLAIMED' AND claim_until <= :now))
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ReservationExpirationWork> findClaimableForUpdate(@Param("id") UUID id, @Param("now") Instant now);

    @Query(value = "SELECT * FROM reservation_expiration_work WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<ReservationExpirationWork> findByIdForUpdate(@Param("id") UUID id);
    Optional<ReservationExpirationWork> findByOrderId(UUID orderId);

    long countByStatus(ReservationExpirationWorkStatus status);

    @Query("SELECT MIN(w.failedAt) FROM ReservationExpirationWork w WHERE w.status = 'FAILED'")
    Optional<Instant> findOldestFailedAt();
}
