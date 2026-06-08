package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NotificationDeliveryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void binding_shouldUseSafeDefaults() {
        contextRunner.run(context -> {
            NotificationDeliveryProperties properties = context.getBean(NotificationDeliveryProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.batchSize()).isEqualTo(25);
            assertThat(properties.fixedDelay()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.maxAttempts()).isEqualTo(3);
        });
    }

    @Test
    void binding_shouldApplyConfiguredValues() {
        contextRunner
                .withPropertyValues(
                        "app.notification.delivery.enabled=true",
                        "app.notification.delivery.batch-size=9",
                        "app.notification.delivery.fixed-delay=PT5S",
                        "app.notification.delivery.max-attempts=5")
                .run(context -> {
                    NotificationDeliveryProperties properties = context.getBean(NotificationDeliveryProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.batchSize()).isEqualTo(9);
                    assertThat(properties.fixedDelay()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.maxAttempts()).isEqualTo(5);
                });
    }

    @Configuration
    @EnableConfigurationProperties(NotificationDeliveryProperties.class)
    static class TestConfiguration {
    }
}
