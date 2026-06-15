package com.company.shop.module.order.outbox;

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
            String eventType) {
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

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
