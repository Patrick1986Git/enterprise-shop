package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class ReservationExpirationClaimService {
    static final String EXPIRED_CLAIM_BUDGET_EXHAUSTED =
            "Claim lease expired after the reservation expiration attempt budget was exhausted";
    private final ReservationExpirationWorkRepository repository;
    private final ReservationExpirationProperties properties;
    public ReservationExpirationClaimService(ReservationExpirationWorkRepository repository,
            ReservationExpirationProperties properties) {
        this.repository = repository; this.properties = properties;
    }
    @Transactional
    public Optional<ReservationExpirationClaim> claim(UUID workId) {
        return repository.findClaimableForUpdate(workId).flatMap(work -> {
            Instant now = repository.findCurrentTimestamp();
            if (!work.hasClaimBudget(properties.maxAttempts())) {
                work.failClaimBudgetExhausted(now, EXPIRED_CLAIM_BUDGET_EXHAUSTED);
                return Optional.empty();
            }
            UUID token = work.claim(now, now.plus(properties.claimLease()));
            return Optional.of(new ReservationExpirationClaim(work.getId(), work.getOrderId(), token));
        });
    }
    @Transactional
    public void complete(ReservationExpirationClaim claim) {
        repository.findByIdForUpdate(claim.workId()).ifPresent(work ->
                work.complete(claim.claimToken(), repository.findCurrentTimestamp()));
    }
    @Transactional
    public boolean retry(ReservationExpirationClaim claim, String error) {
        return repository.findByIdForUpdate(claim.workId()).map(work -> {
            Instant now = repository.findCurrentTimestamp();
            work.retry(claim.claimToken(), now, now.plus(properties.retryDelay()), error, properties.maxAttempts());
            return work.getStatus() == ReservationExpirationWorkStatus.FAILED;
        }).orElse(false);
    }
}
