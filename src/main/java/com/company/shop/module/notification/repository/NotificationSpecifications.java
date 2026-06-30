package com.company.shop.module.notification.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.company.shop.module.notification.NotificationAdminSearchCriteria;
import com.company.shop.module.notification.NotificationDeliveryState;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;

import jakarta.persistence.criteria.Predicate;

public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    public static Specification<Notification> adminFilters(NotificationAdminSearchCriteria criteria) {
        return adminFilters(criteria, criteria.deliveryState() == null ? null : Instant.now());
    }

    public static Specification<Notification> adminFilters(NotificationAdminSearchCriteria criteria, Instant now) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }

            if (criteria.deliveryState() == NotificationDeliveryState.DUE_PENDING) {
                predicates.add(cb.equal(root.get("status"), NotificationStatus.PENDING));
                predicates.add(cb.or(
                        cb.isNull(root.get("nextAttemptAt")),
                        cb.lessThanOrEqualTo(root.get("nextAttemptAt"), now)));
            }

            if (criteria.deliveryState() == NotificationDeliveryState.SCHEDULED_PENDING) {
                predicates.add(cb.equal(root.get("status"), NotificationStatus.PENDING));
                predicates.add(cb.greaterThan(root.get("nextAttemptAt"), now));
            }

            if (criteria.sourceEventId() != null) {
                predicates.add(cb.equal(root.get("sourceEventId"), criteria.sourceEventId()));
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

            if (criteria.lastRequeuedBy() != null && !criteria.lastRequeuedBy().isBlank()) {
                String pattern = "%" + criteria.lastRequeuedBy().trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.like(cb.lower(root.get("lastRequeuedBy")), pattern));
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

            if (criteria.lastAttemptFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastAttemptAt"), criteria.lastAttemptFrom()));
            }

            if (criteria.lastAttemptTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastAttemptAt"), criteria.lastAttemptTo()));
            }

            if (criteria.lastRequeuedFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastRequeuedAt"), criteria.lastRequeuedFrom()));
            }

            if (criteria.lastRequeuedTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastRequeuedAt"), criteria.lastRequeuedTo()));
            }

            if (criteria.createdFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.createdFrom()));
            }

            if (criteria.createdTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), criteria.createdTo()));
            }

            if (criteria.sentFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("sentAt"), criteria.sentFrom()));
            }

            if (criteria.sentTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("sentAt"), criteria.sentTo()));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
