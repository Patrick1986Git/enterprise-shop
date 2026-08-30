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

        assertThat(compose).contains("APP_NOTIFICATION_SMTP_ENABLED: ${NOTIFICATION_SMTP_ENABLED:-false}");
        assertThat(compose).doesNotContain("SPRING_MAIL_HOST:", "SPRING_MAIL_PORT:");
    }
}
