package com.company.shop.module.order.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

public final class OutboxEventSpecifications {

    private OutboxEventSpecifications() {
    }

    public static Specification<OutboxEvent> adminFilters(OutboxEventAdminSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }

            if (criteria.aggregateType() != null && !criteria.aggregateType().isBlank()) {
                String pattern = "%" + criteria.aggregateType().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("aggregateType")), pattern));
            }

            if (criteria.aggregateId() != null) {
                predicates.add(cb.equal(root.get("aggregateId"), criteria.aggregateId()));
            }

            if (criteria.eventType() != null && !criteria.eventType().isBlank()) {
                String pattern = "%" + criteria.eventType().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("eventType")), pattern));
            }

            if (criteria.lastErrorContains() != null && !criteria.lastErrorContains().isBlank()) {
                String pattern = "%" + criteria.lastErrorContains().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("lastError")), pattern));
            }

            if (criteria.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.createdFrom()));
            }

            if (criteria.createdTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), criteria.createdTo()));
            }

            if (criteria.lastAttemptFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastAttemptAt"), criteria.lastAttemptFrom()));
            }

            if (criteria.lastAttemptTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastAttemptAt"), criteria.lastAttemptTo()));
            }

            if (criteria.attemptsMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("attempts"), criteria.attemptsMin()));
            }

            if (criteria.attemptsMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("attempts"), criteria.attemptsMax()));
            }

            if (Boolean.TRUE.equals(criteria.requeuedOnly())) {
                predicates.add(cb.greaterThan(root.get("requeueCount"), 0));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
