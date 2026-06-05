package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OutboxProcessingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void binding_shouldUseSafeDefaults() {
        contextRunner.run(context -> {
            OutboxProcessingProperties properties = context.getBean(OutboxProcessingProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.batchSize()).isEqualTo(25);
        });
    }

    @Test
    void binding_shouldApplyConfiguredValues() {
        contextRunner
                .withPropertyValues(
                        "app.outbox.processing.enabled=true",
                        "app.outbox.processing.batch-size=9")
                .run(context -> {
                    OutboxProcessingProperties properties = context.getBean(OutboxProcessingProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.batchSize()).isEqualTo(9);
                });
    }

    @Configuration
    @EnableConfigurationProperties(OutboxProcessingProperties.class)
    static class TestConfiguration {
    }
}
