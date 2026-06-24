package com.company.shop.module.notification.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;

import jakarta.persistence.criteria.Predicate;

public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    public static Specification<Notification> adminFilters(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly) {
        return adminFilters(status, type, recipient, lastErrorContains, requeuedOnly, null, null);
    }

    public static Specification<Notification> adminFilters(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(root.get("type"), type.trim()));
            }

            if (recipient != null && !recipient.isBlank()) {
                String pattern = "%" + recipient.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("recipient")), pattern));
            }

            if (lastErrorContains != null && !lastErrorContains.isBlank()) {
                String pattern = "%" + lastErrorContains.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("lastError")), pattern));
            }

            if (Boolean.TRUE.equals(requeuedOnly)) {
                predicates.add(cb.greaterThan(root.get("requeueCount"), 0));
            }

            if (attemptsMin != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("attempts"), attemptsMin));
            }

            if (attemptsMax != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("attempts"), attemptsMax));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
