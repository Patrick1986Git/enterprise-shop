package com.company.shop.module.order.expiration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.order.reservation-expiration")
public class ReservationExpirationProperties {
    private Duration duration = Duration.ofMinutes(30);
    private Duration fixedDelay = Duration.ofSeconds(10);
    private Duration retryDelay = Duration.ofMinutes(1);
    private Duration claimLease = Duration.ofMinutes(5);
    private int batchSize = 25;
    private int maxAttempts = 10;

    public Duration duration() { return duration; }
    public Duration fixedDelay() { return fixedDelay; }
    public Duration retryDelay() { return retryDelay; }
    public Duration claimLease() { return claimLease; }
    public int batchSize() { return batchSize; }
    public int maxAttempts() { return maxAttempts; }
    public void setDuration(Duration value) { duration = positive(value, "duration"); }
    public void setFixedDelay(Duration value) { fixedDelay = positive(value, "fixedDelay"); }
    public void setRetryDelay(Duration value) { retryDelay = positive(value, "retryDelay"); }
    public void setClaimLease(Duration value) { claimLease = positive(value, "claimLease"); }
    public void setBatchSize(int value) { if (value < 1) throw new IllegalArgumentException("batchSize must be positive"); batchSize = value; }
    public void setMaxAttempts(int value) { if (value < 1) throw new IllegalArgumentException("maxAttempts must be positive"); maxAttempts = value; }
    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
