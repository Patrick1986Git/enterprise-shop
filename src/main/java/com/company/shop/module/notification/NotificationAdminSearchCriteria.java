package com.company.shop.module.notification;

import java.time.Instant;

import com.company.shop.module.notification.entity.NotificationStatus;

public record NotificationAdminSearchCriteria(
        NotificationStatus status,
        NotificationDeliveryState deliveryState,
        String type,
        String recipient,
        String lastErrorContains,
        Boolean requeuedOnly,
        Integer attemptsMin,
        Integer attemptsMax,
        Instant lastAttemptFrom,
        Instant lastAttemptTo,
        Instant sentFrom,
        Instant sentTo) {

    public NotificationAdminSearchCriteria(
            NotificationStatus status,
            NotificationDeliveryState deliveryState,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax,
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        this(
                status,
                deliveryState,
                type,
                recipient,
                lastErrorContains,
                requeuedOnly,
                attemptsMin,
                attemptsMax,
                lastAttemptFrom,
                lastAttemptTo,
                null,
                null);
    }

    public NotificationAdminSearchCriteria(
            NotificationStatus status,
            String type,
            String recipient,
            String lastErrorContains,
            Boolean requeuedOnly,
            Integer attemptsMin,
            Integer attemptsMax,
            Instant lastAttemptFrom,
            Instant lastAttemptTo) {
        this(
                status,
                null,
                type,
                recipient,
                lastErrorContains,
                requeuedOnly,
                attemptsMin,
                attemptsMax,
                lastAttemptFrom,
                lastAttemptTo,
                null,
                null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private NotificationStatus status;
        private NotificationDeliveryState deliveryState;
        private String type;
        private String recipient;
        private String lastErrorContains;
        private Boolean requeuedOnly;
        private Integer attemptsMin;
        private Integer attemptsMax;
        private Instant lastAttemptFrom;
        private Instant lastAttemptTo;
        private Instant sentFrom;
        private Instant sentTo;

        private Builder() {
        }

        public Builder status(NotificationStatus status) {
            this.status = status;
            return this;
        }

        public Builder deliveryState(NotificationDeliveryState deliveryState) {
            this.deliveryState = deliveryState;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder recipient(String recipient) {
            this.recipient = recipient;
            return this;
        }

        public Builder lastErrorContains(String lastErrorContains) {
            this.lastErrorContains = lastErrorContains;
            return this;
        }

        public Builder requeuedOnly(Boolean requeuedOnly) {
            this.requeuedOnly = requeuedOnly;
            return this;
        }

        public Builder attemptsMin(Integer attemptsMin) {
            this.attemptsMin = attemptsMin;
            return this;
        }

        public Builder attemptsMax(Integer attemptsMax) {
            this.attemptsMax = attemptsMax;
            return this;
        }

        public Builder lastAttemptFrom(Instant lastAttemptFrom) {
            this.lastAttemptFrom = lastAttemptFrom;
            return this;
        }

        public Builder lastAttemptTo(Instant lastAttemptTo) {
            this.lastAttemptTo = lastAttemptTo;
            return this;
        }

        public Builder sentFrom(Instant sentFrom) {
            this.sentFrom = sentFrom;
            return this;
        }

        public Builder sentTo(Instant sentTo) {
            this.sentTo = sentTo;
            return this;
        }

        public NotificationAdminSearchCriteria build() {
            return new NotificationAdminSearchCriteria(
                    status,
                    deliveryState,
                    type,
                    recipient,
                    lastErrorContains,
                    requeuedOnly,
                    attemptsMin,
                    attemptsMax,
                    lastAttemptFrom,
                    lastAttemptTo,
                    sentFrom,
                    sentTo);
        }
    }
}
