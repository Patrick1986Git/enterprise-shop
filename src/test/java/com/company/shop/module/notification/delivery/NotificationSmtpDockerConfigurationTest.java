package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class NotificationSmtpDockerConfigurationTest {

    @Test
    void defaultComposeConfiguration_shouldKeepSmtpDisabledWithoutActivatingSpringMail() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertThat(compose).contains("APP_NOTIFICATION_DELIVERY_ENABLED: ${NOTIFICATION_DELIVERY_ENABLED:-false}");
        assertThat(compose).contains(
                "APP_NOTIFICATION_DELIVERY_BATCH_SIZE: ${NOTIFICATION_DELIVERY_BATCH_SIZE:-25}",
                "APP_NOTIFICATION_DELIVERY_FIXED_DELAY: ${NOTIFICATION_DELIVERY_FIXED_DELAY:-PT10S}",
                "APP_NOTIFICATION_DELIVERY_MAX_ATTEMPTS: ${NOTIFICATION_DELIVERY_MAX_ATTEMPTS:-3}",
                "APP_NOTIFICATION_DELIVERY_RETRY_DELAY: ${NOTIFICATION_DELIVERY_RETRY_DELAY:-PT1M}",
                "APP_NOTIFICATION_DELIVERY_CLAIM_DURATION: ${NOTIFICATION_DELIVERY_CLAIM_DURATION:-PT5M}");
        assertThat(compose).contains("APP_NOTIFICATION_SMTP_ENABLED: ${NOTIFICATION_SMTP_ENABLED:-false}");
        assertThat(compose).doesNotContain("SPRING_MAIL_HOST:", "SPRING_MAIL_PORT:");
    }
}
