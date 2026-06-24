package com.company.shop.module.notification.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.notification.NotificationAdminSearchCriteria;
import com.company.shop.module.notification.entity.Notification;

import jakarta.persistence.criteria.Predicate;

public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    public static Specification<Notification> adminFilters(NotificationAdminSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }

            if (criteria.type() != null && !criteria.type().isBlank()) {
                predicates.add(cb.equal(root.get("type"), criteria.type().trim()));
            }

            if (criteria.recipient() != null && !criteria.recipient().isBlank()) {
                String pattern = "%" + criteria.recipient().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("recipient")), pattern));
            }

            if (criteria.lastErrorContains() != null && !criteria.lastErrorContains().isBlank()) {
                String pattern = "%" + criteria.lastErrorContains().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("lastError")), pattern));
            }

            if (Boolean.TRUE.equals(criteria.requeuedOnly())) {
                predicates.add(cb.greaterThan(root.get("requeueCount"), 0));
            }

            if (criteria.attemptsMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("attempts"), criteria.attemptsMin()));
            }

            if (criteria.attemptsMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("attempts"), criteria.attemptsMax()));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
