package com.company.shop.module.order.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.outbox.processing")
public class OutboxProcessingProperties {

    private boolean enabled = false;
    private int batchSize = 25;
    private Duration fixedDelay = Duration.ofSeconds(10);
    private Duration retryDelay = Duration.ofMinutes(1);
    private int maxAttempts = 3;

    public boolean enabled() {
        return enabled;
    }

    public int batchSize() {
        return batchSize;
    }

    public Duration fixedDelay() {
        return fixedDelay;
    }

    public Duration retryDelay() {
        return retryDelay;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = positive(fixedDelay, "fixedDelay");
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = positive(retryDelay, "retryDelay");
    }

    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    private Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
