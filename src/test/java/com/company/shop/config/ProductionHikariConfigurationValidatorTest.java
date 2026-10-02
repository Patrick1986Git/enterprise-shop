package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionHikariConfigurationValidatorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProductionHikariConfigurationValidator.class)
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "spring.datasource.hikari.maximum-pool-size=4",
                    "spring.datasource.hikari.minimum-idle=0",
                    "spring.datasource.hikari.connection-timeout=1000",
                    "spring.datasource.hikari.data-source-properties.socketTimeout=30");

    @Test
    void prodContext_shouldRegisterAndRunValidatorForValidSettings() {
        contextRunner.run(context -> {
            assertThatCode(() -> context.getBean(ProductionHikariConfigurationValidator.class))
                    .doesNotThrowAnyException();
            assertThat(context).hasNotFailed();
        });
    }

    @Test
    void prodContext_shouldFailWhenMinimumIdleExceedsMaximumPoolSize() {
        contextRunner.withPropertyValues("spring.datasource.hikari.minimum-idle=5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("spring.datasource.hikari.minimum-idle must not exceed "
                                    + "spring.datasource.hikari.maximum-pool-size");
                });
    }

    @Test
    void validate_shouldAcceptValidDeploymentOwnedSettings() {
        var validator = new ProductionHikariConfigurationValidator(12, 3, 1_000, 30);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_shouldRejectNonPositiveMaximumPoolSize() {
        var validator = new ProductionHikariConfigurationValidator(0, 0, 1_000, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessage("spring.datasource.hikari.maximum-pool-size must be positive");
    }

    @Test
    void validate_shouldRejectNegativeMinimumIdle() {
        var validator = new ProductionHikariConfigurationValidator(4, -1, 1_000, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessage("spring.datasource.hikari.minimum-idle must not be negative");
    }

    @Test
    void validate_shouldRejectMinimumIdleAboveMaximumPoolSize() {
        var validator = new ProductionHikariConfigurationValidator(4, 5, 1_000, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessage("spring.datasource.hikari.minimum-idle must not exceed "
                        + "spring.datasource.hikari.maximum-pool-size");
    }

    @Test
    void validate_shouldRejectConnectionTimeoutBelowHikariMinimum() {
        var validator = new ProductionHikariConfigurationValidator(4, 0, 249, 30);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessage("spring.datasource.hikari.connection-timeout must be at least 250 milliseconds");
    }

    @Test
    void validate_shouldRejectDisabledSocketTimeout() {
        var validator = new ProductionHikariConfigurationValidator(4, 0, 1_000, 0);

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessage("spring.datasource.hikari.data-source-properties.socketTimeout must be positive");
    }
}
