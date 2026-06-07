package com.company.shop.module.notification.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notification.smtp")
public class NotificationSmtpProperties {

    private boolean enabled = false;
    private String from = "no-reply@enterprise-shop.local";

    public boolean enabled() {
        return enabled;
    }

    public String from() {
        return from;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setFrom(String from) {
        this.from = from;
    }
}
