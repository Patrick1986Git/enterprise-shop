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
            String recipient) {
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

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
