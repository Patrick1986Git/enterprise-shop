package com.company.shop.module.order.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.order.entity.Order;

import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, UUID> {
	Page<Order> findByUserId(UUID userId, Pageable pageable);

	java.util.Optional<Order> findByUserIdAndCheckoutIdempotencyKey(UUID userId, String checkoutIdempotencyKey);

	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text) || ':' || :idempotencyKey, 0))", nativeQuery = true)
	Object acquireCheckoutIdempotencyLock(@Param("userId") UUID userId,
			@Param("idempotencyKey") String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.id = :id")
	java.util.Optional<Order> findByIdForUpdate(@Param("id") UUID id);
}
