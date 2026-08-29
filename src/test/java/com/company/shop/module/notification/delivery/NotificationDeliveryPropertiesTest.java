package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
            assertThat(properties.retryDelay()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.claimDuration()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void binding_shouldApplyConfiguredValues() {
        contextRunner
                .withPropertyValues(
                        "app.notification.delivery.enabled=true",
                        "app.notification.delivery.batch-size=9",
                        "app.notification.delivery.fixed-delay=PT5S",
                        "app.notification.delivery.max-attempts=5",
                        "app.notification.delivery.retry-delay=PT30S",
                        "app.notification.delivery.claim-duration=PT2M")
                .run(context -> {
                    NotificationDeliveryProperties properties = context.getBean(NotificationDeliveryProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.batchSize()).isEqualTo(9);
                    assertThat(properties.fixedDelay()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.maxAttempts()).isEqualTo(5);
                    assertThat(properties.retryDelay()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.claimDuration()).isEqualTo(Duration.ofMinutes(2));
                });
    }

    @Test
    void setters_shouldRejectInvalidLeaseRetryAndSchedulingValues() {
        var properties = new NotificationDeliveryProperties();

        assertThatIllegalArgumentException().isThrownBy(() -> properties.setBatchSize(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setMaxAttempts(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setFixedDelay(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setRetryDelay(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.setClaimDuration(null));
    }

    @Test
    void binding_shouldFailClosedForInvalidWorkerValues() {
        contextRunner.withPropertyValues("app.notification.delivery.claim-duration=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(NotificationDeliveryProperties.class)
    static class TestConfiguration {
    }
}
