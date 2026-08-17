package com.company.shop.module.order.expiration;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class ReservationExpirationClaimService {
    private final ReservationExpirationWorkRepository repository;
    private final ReservationExpirationProperties properties;
    private final Clock clock;
    public ReservationExpirationClaimService(ReservationExpirationWorkRepository repository,
            ReservationExpirationProperties properties, Clock clock) {
        this.repository = repository; this.properties = properties; this.clock = clock;
    }
    @Transactional
    public Optional<ReservationExpirationClaim> claim(UUID workId) {
        Instant now = clock.instant();
        return repository.findClaimableForUpdate(workId, now).map(work -> {
            UUID token = work.claim(now, now.plus(properties.claimLease()));
            return new ReservationExpirationClaim(work.getId(), work.getOrderId(), token);
        });
    }
    @Transactional
    public void complete(ReservationExpirationClaim claim) {
        repository.findByIdForUpdate(claim.workId()).ifPresent(work -> work.complete(claim.claimToken(), clock.instant()));
    }
    @Transactional
    public boolean retry(ReservationExpirationClaim claim, String error) {
        Instant now = clock.instant();
        return repository.findByIdForUpdate(claim.workId()).map(work -> {
            work.retry(claim.claimToken(), now, now.plus(properties.retryDelay()), error, properties.maxAttempts());
            return work.getStatus() == ReservationExpirationWorkStatus.FAILED;
        }).orElse(false);
    }
}
