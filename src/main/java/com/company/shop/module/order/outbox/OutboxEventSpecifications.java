package com.company.shop.module.order.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

public final class OutboxEventSpecifications {

    private OutboxEventSpecifications() {
    }

    public static Specification<OutboxEvent> adminFilters(
            OutboxEventStatus status,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String lastErrorContains,
            Instant createdFrom,
            Instant createdTo,
            Instant lastAttemptFrom,
            Instant lastAttemptTo,
            Integer attemptsMin,
            Integer attemptsMax,
            Boolean requeuedOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (aggregateType != null && !aggregateType.isBlank()) {
                String pattern = "%" + aggregateType.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("aggregateType")), pattern));
            }

            if (aggregateId != null) {
                predicates.add(cb.equal(root.get("aggregateId"), aggregateId));
            }

            if (eventType != null && !eventType.isBlank()) {
                String pattern = "%" + eventType.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("eventType")), pattern));
            }

            if (lastErrorContains != null && !lastErrorContains.isBlank()) {
                String pattern = "%" + lastErrorContains.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("lastError")), pattern));
            }

            if (createdFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            }

            if (createdTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), createdTo));
            }

            if (lastAttemptFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastAttemptAt"), lastAttemptFrom));
            }

            if (lastAttemptTo != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastAttemptAt"), lastAttemptTo));
            }

            if (attemptsMin != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("attempts"), attemptsMin));
            }

            if (attemptsMax != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("attempts"), attemptsMax));
            }

            if (Boolean.TRUE.equals(requeuedOnly)) {
                predicates.add(cb.greaterThan(root.get("requeueCount"), 0));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
