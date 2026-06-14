package com.company.shop.module.notification.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.notification.entity.NotificationAdminActionLog;
import com.company.shop.module.notification.entity.NotificationAdminActionType;

import jakarta.persistence.criteria.Predicate;

public final class NotificationAdminActionLogSpecifications {

    private NotificationAdminActionLogSpecifications() {
    }

    public static Specification<NotificationAdminActionLog> adminFilters(
            UUID notificationId,
            NotificationAdminActionType actionType,
            String actorEmail) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (notificationId != null) {
                predicates.add(criteriaBuilder.equal(root.get("notificationId"), notificationId));
            }
            if (actionType != null) {
                predicates.add(criteriaBuilder.equal(root.get("actionType"), actionType));
            }
            if (actorEmail != null && !actorEmail.isBlank()) {
                String normalizedActorEmail = actorEmail.trim().toLowerCase(Locale.ROOT);
                predicates.add(criteriaBuilder.like(
                        criteriaBuilder.lower(root.get("actorEmail")),
                        "%" + normalizedActorEmail + "%"));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
