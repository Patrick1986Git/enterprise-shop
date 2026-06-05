package com.company.shop.module.order.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.outbox.processing")
public class OutboxProcessingProperties {

    private boolean enabled = false;
    private int batchSize = 25;
    private Duration fixedDelay = Duration.ofSeconds(10);

    public boolean enabled() {
        return enabled;
    }

    public int batchSize() {
        return batchSize;
    }

    public Duration fixedDelay() {
        return fixedDelay;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setFixedDelay(Duration fixedDelay) {
        this.fixedDelay = fixedDelay;
    }
}
