package com.company.shop.module.order.expiration;

import java.time.Instant;
import java.util.UUID;

import com.company.shop.common.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation_expiration_work")
public class ReservationExpirationWork extends BaseEntity {
    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationExpirationWorkStatus status;
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "claim_token")
    private UUID claimToken;
    @Column(name = "claim_until")
    private Instant claimUntil;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "failed_at")
    private Instant failedAt;
    @Column(name = "recovery_count", nullable = false)
    private int recoveryCount;
    @Column(name = "last_recovered_at")
    private Instant lastRecoveredAt;
    @Column(name = "last_recovered_by", length = 255)
    private String lastRecoveredBy;

    protected ReservationExpirationWork() {}
    public ReservationExpirationWork(UUID orderId, Instant dueAt) {
        this.orderId = orderId;
        this.dueAt = dueAt;
        this.nextAttemptAt = dueAt;
        this.status = ReservationExpirationWorkStatus.PENDING;
    }
    public UUID claim(Instant now, Instant claimUntil) {
        this.status = ReservationExpirationWorkStatus.CLAIMED;
        this.claimToken = UUID.randomUUID();
        this.claimUntil = claimUntil;
        this.attempts++;
        this.lastError = null;
        return claimToken;
    }
    public void complete(UUID token, Instant now) { requireClaim(token); status = ReservationExpirationWorkStatus.COMPLETED; completedAt = now; clearClaim(); }
    public void retry(UUID token, Instant now, Instant next, String error, int maxAttempts) {
        requireClaim(token); status = attempts >= maxAttempts ? ReservationExpirationWorkStatus.FAILED : ReservationExpirationWorkStatus.PENDING;
        nextAttemptAt = next; lastError = truncate(error); clearClaim();
        if (status == ReservationExpirationWorkStatus.FAILED) failedAt = now;
    }
    public void requeueFailed(Instant now, String recoveredBy) {
        if (status != ReservationExpirationWorkStatus.FAILED) {
            throw new IllegalStateException("Only failed reservation expiration work can be recovered");
        }
        status = ReservationExpirationWorkStatus.PENDING;
        nextAttemptAt = now;
        recoveryCount++;
        lastRecoveredAt = now;
        lastRecoveredBy = recoveredBy;
    }
    public void completeFailedForTerminalOrder(Instant now, String recoveredBy) {
        if (status != ReservationExpirationWorkStatus.FAILED) {
            throw new IllegalStateException("Only failed reservation expiration work can be recovered");
        }
        status = ReservationExpirationWorkStatus.COMPLETED;
        completedAt = now;
        recoveryCount++;
        lastRecoveredAt = now;
        lastRecoveredBy = recoveredBy;
    }
    private void requireClaim(UUID token) { if (claimToken == null || !claimToken.equals(token)) throw new IllegalStateException("Expiration claim is no longer owned"); }
    private void clearClaim() { claimToken = null; claimUntil = null; }
    private String truncate(String value) { if (value == null) return null; return value.substring(0, Math.min(1000, value.length())); }
    public UUID getOrderId() { return orderId; }
    public ReservationExpirationWorkStatus getStatus() { return status; }
    public Instant getDueAt() { return dueAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getClaimUntil() { return claimUntil; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getFailedAt() { return failedAt; }
    public int getRecoveryCount() { return recoveryCount; }
    public Instant getLastRecoveredAt() { return lastRecoveredAt; }
    public String getLastRecoveredBy() { return lastRecoveredBy; }
}
