package com.company.shop.module.notification.delivery;

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

    public NotificationDeliveryTransactionalWorker(NotificationRepository repository,
            NotificationDeliveryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public List<ClaimedNotification> claimBatch(int batchSize) {
        repository.failExhaustedExpiredClaims(properties.maxAttempts());
        return repository.findClaimableBatchForUpdate(batchSize, properties.maxAttempts()).stream()
                .map(this::claim)
                .toList();
    }

    private ClaimedNotification claim(Notification notification) {
        Instant now = repository.currentDatabaseTime();
        UUID token = UUID.randomUUID();
        notification.claim(token, now, now.plus(properties.claimDuration()));
        return new ClaimedNotification(notification, token);
    }

    @Transactional
    public boolean finalizeSuccess(UUID id, UUID token) {
        return repository.findByIdForUpdate(id).map(notification -> {
            if (!notification.ownsClaim(token)) return false;
            return notification.finalizeSent(token, repository.currentDatabaseTime());
        }).orElse(false);
    }

    @Transactional
    public boolean finalizeFailure(UUID id, UUID token, String error) {
        return repository.findByIdForUpdate(id)
                .map(notification -> {
                    if (!notification.ownsClaim(token)) return false;
                    Instant failedAt = repository.currentDatabaseTime();
                    return notification.finalizeFailed(token, error, properties.maxAttempts(),
                            failedAt.plus(properties.retryDelay()));
                })
                .orElse(false);
    }
}
