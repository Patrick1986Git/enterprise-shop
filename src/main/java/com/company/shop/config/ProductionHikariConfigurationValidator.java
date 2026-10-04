package com.company.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@Profile("prod")
public class ProductionHikariConfigurationValidator {

    private static final long HIKARI_MINIMUM_CONNECTION_TIMEOUT_MILLISECONDS = 250;

    private final int maximumPoolSize;
    private final int minimumIdle;
    private final long connectionTimeoutMilliseconds;
    private final int socketTimeoutSeconds;

    public ProductionHikariConfigurationValidator(
            @Value("${spring.datasource.hikari.maximum-pool-size}") int maximumPoolSize,
            @Value("${spring.datasource.hikari.minimum-idle}") int minimumIdle,
            @Value("${spring.datasource.hikari.connection-timeout}") long connectionTimeoutMilliseconds,
            @Value("${spring.datasource.hikari.data-source-properties.socketTimeout}") int socketTimeoutSeconds) {
        this.maximumPoolSize = maximumPoolSize;
        this.minimumIdle = minimumIdle;
        this.connectionTimeoutMilliseconds = connectionTimeoutMilliseconds;
        this.socketTimeoutSeconds = socketTimeoutSeconds;
    }

    @PostConstruct
    void validate() {
        if (maximumPoolSize < 1) {
            throw new IllegalStateException("spring.datasource.hikari.maximum-pool-size must be positive");
        }
        if (minimumIdle < 0) {
            throw new IllegalStateException("spring.datasource.hikari.minimum-idle must not be negative");
        }
        if (minimumIdle > maximumPoolSize) {
            throw new IllegalStateException(
                    "spring.datasource.hikari.minimum-idle must not exceed spring.datasource.hikari.maximum-pool-size");
        }
        if (connectionTimeoutMilliseconds < HIKARI_MINIMUM_CONNECTION_TIMEOUT_MILLISECONDS) {
            throw new IllegalStateException(
                    "spring.datasource.hikari.connection-timeout must be at least 250 milliseconds");
        }
        if (socketTimeoutSeconds < 1) {
            throw new IllegalStateException(
                    "spring.datasource.hikari.data-source-properties.socketTimeout must be positive");
        }
    }
}
