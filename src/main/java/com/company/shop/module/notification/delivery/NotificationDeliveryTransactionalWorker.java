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
        Instant now = Instant.now();
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
        return repository.findByIdForUpdate(id).map(n -> n.finalizeSent(token)).orElse(false);
    }

    @Transactional
    public boolean finalizeFailure(UUID id, UUID token, String error) {
        return repository.findByIdForUpdate(id)
                .map(n -> n.finalizeFailed(token, error, properties.maxAttempts(),
                        Instant.now().plus(properties.retryDelay())))
                .orElse(false);
    }
}
