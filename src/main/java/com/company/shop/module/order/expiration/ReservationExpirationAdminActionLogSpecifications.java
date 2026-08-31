package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import jakarta.persistence.criteria.Predicate;

public final class ReservationExpirationAdminActionLogSpecifications {
    private ReservationExpirationAdminActionLogSpecifications() {}

    public static Specification<ReservationExpirationAdminActionLog> adminFilters(
            UUID orderId, UUID workId, ReservationExpirationAdminActionType actionType,
            ReservationExpirationAdminActionOutcome outcome, String actorEmail,
            Instant createdFrom, Instant createdTo) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (orderId != null) predicates.add(criteriaBuilder.equal(root.get("orderId"), orderId));
            if (workId != null) predicates.add(criteriaBuilder.equal(root.get("workId"), workId));
            if (actionType != null) predicates.add(criteriaBuilder.equal(root.get("actionType"), actionType));
            if (outcome != null) predicates.add(criteriaBuilder.equal(root.get("outcome"), outcome));
            if (actorEmail != null && !actorEmail.isBlank()) {
                String normalized = actorEmail.trim().toLowerCase(Locale.ROOT);
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("actorEmail")), "%" + normalized + "%"));
            }
            if (createdFrom != null) predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), createdFrom));
            if (createdTo != null) predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), createdTo));
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
