package com.company.shop.module.notification.delivery;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.repository.NotificationRepository;

@Component
public class NotificationDeliveryTransactionalWorker {
    private final NotificationRepository repository;
    private final NotificationDeliveryProperties properties;
    private final Clock clock;

    public NotificationDeliveryTransactionalWorker(NotificationRepository repository,
            NotificationDeliveryProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedNotification> claimBatch(int batchSize) {
        Instant now = clock.instant();
        repository.failExhaustedExpiredClaims(now, properties.maxAttempts());
        return repository.findClaimableBatchForUpdate(batchSize, now, properties.maxAttempts()).stream()
                .map(notification -> claim(notification, now))
                .toList();
    }

    private ClaimedNotification claim(Notification notification, Instant now) {
        UUID token = UUID.randomUUID();
        notification.claim(token, now, now.plus(properties.claimDuration()));
        return new ClaimedNotification(notification, token);
    }

    @Transactional
    public boolean finalizeSuccess(UUID id, UUID token) {
        return repository.findByIdForUpdate(id).map(notification -> {
            if (!notification.ownsClaim(token)) return false;
            return notification.finalizeSent(token, clock.instant());
        }).orElse(false);
    }

    @Transactional
    public boolean finalizeFailure(UUID id, UUID token, String error) {
        return repository.findByIdForUpdate(id)
                .map(notification -> {
                    if (!notification.ownsClaim(token)) return false;
                    Instant failedAt = clock.instant();
                    return notification.finalizeFailed(token, error, properties.maxAttempts(),
                            failedAt.plus(properties.retryDelay()));
                })
                .orElse(false);
    }
}
