package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationSmtpPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void binding_shouldUseSafeDefaults() {
        contextRunner.run(context -> {
            NotificationSmtpProperties properties = context.getBean(NotificationSmtpProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.from()).isEqualTo("no-reply@enterprise-shop.local");
        });
    }

    @Test
    void binding_shouldApplyConfiguredValues() {
        contextRunner
                .withPropertyValues(
                        "app.notification.smtp.enabled=true",
                        "app.notification.smtp.from=no-reply@example.com")
                .run(context -> {
                    NotificationSmtpProperties properties = context.getBean(NotificationSmtpProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.from()).isEqualTo("no-reply@example.com");
                });
    }

    @Configuration
    @EnableConfigurationProperties(NotificationSmtpProperties.class)
    static class TestConfiguration {
    }
}
