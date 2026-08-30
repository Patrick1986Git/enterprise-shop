package com.company.shop.module.notification.delivery;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notification.smtp")
public class NotificationSmtpProperties {

    private boolean enabled = false;
    private String from = "no-reply@enterprise-shop.local";
    private Duration connectionTimeout = Duration.ofSeconds(30);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration writeTimeout = Duration.ofSeconds(30);

    public boolean enabled() {
        return enabled;
    }

    public String from() {
        return from;
    }

    public Duration connectionTimeout() { return connectionTimeout; }
    public Duration readTimeout() { return readTimeout; }
    public Duration writeTimeout() { return writeTimeout; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public void setConnectionTimeout(Duration value) { connectionTimeout = validJavaMailTimeout(value, "connectionTimeout"); }
    public void setReadTimeout(Duration value) { readTimeout = validJavaMailTimeout(value, "readTimeout"); }
    public void setWriteTimeout(Duration value) { writeTimeout = validJavaMailTimeout(value, "writeTimeout"); }

    private Duration validJavaMailTimeout(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        if (value.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must not exceed " + Integer.MAX_VALUE + " milliseconds");
        }
        return value;
    }
}
