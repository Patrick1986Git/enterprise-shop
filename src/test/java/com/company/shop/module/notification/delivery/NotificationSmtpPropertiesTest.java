package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

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
            assertThat(properties.connectionTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.writeTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void binding_shouldApplyConfiguredValues() {
        contextRunner
                .withPropertyValues(
                        "app.notification.smtp.enabled=true",
                        "app.notification.smtp.from=no-reply@example.com",
                        "app.notification.smtp.connection-timeout=PT5S",
                        "app.notification.smtp.read-timeout=PT6S",
                        "app.notification.smtp.write-timeout=PT7S")
                .run(context -> {
                    NotificationSmtpProperties properties = context.getBean(NotificationSmtpProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.from()).isEqualTo("no-reply@example.com");
                    assertThat(properties.connectionTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(6));
                    assertThat(properties.writeTimeout()).isEqualTo(Duration.ofSeconds(7));
                });
    }

    @Test
    void timeoutSetters_shouldRejectMissingNonPositiveAndUnsupportedValues() {
        var properties = new NotificationSmtpProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setConnectionTimeout(null));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setReadTimeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setWriteTimeout(Duration.ofMillis(-1)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> properties.setConnectionTimeout(Duration.ofMillis((long) Integer.MAX_VALUE + 1)));
    }

    @Test
    void binding_shouldFailClosedForInvalidTimeout() {
        contextRunner.withPropertyValues("app.notification.smtp.connection-timeout=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(NotificationSmtpProperties.class)
    static class TestConfiguration {
    }
}
