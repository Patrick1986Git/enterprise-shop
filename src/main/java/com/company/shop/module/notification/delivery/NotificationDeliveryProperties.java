package com.company.shop.module.notification.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notification.delivery")
public class NotificationDeliveryProperties {

    private boolean enabled = false;
    private int batchSize = 25;
    private Duration fixedDelay = Duration.ofSeconds(10);
    private int maxAttempts = 3;
    private Duration retryDelay = Duration.ofMinutes(1);
    private Duration claimDuration = Duration.ofMinutes(5);

    public boolean enabled() {
        return enabled;
    }

    public int batchSize() {
        return batchSize;
    }

    public Duration fixedDelay() {
        return fixedDelay;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration retryDelay() {
        return retryDelay;
    }
    public Duration claimDuration() { return claimDuration; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = positive(fixedDelay, "fixedDelay");
    }

    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        this.maxAttempts = maxAttempts;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = positive(retryDelay, "retryDelay");
    }
    public void setClaimDuration(Duration claimDuration) { this.claimDuration = positive(claimDuration, "claimDuration"); }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
