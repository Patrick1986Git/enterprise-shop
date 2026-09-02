package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ApplicationConfigurationProfileTest {

    private static final String TEST_DATABASE_PROPERTIES = "DATABASE_URL=jdbc:postgresql://localhost:1/unavailable";

    @Test
    void baseConfiguration_shouldNotActivateDevelopmentProfileOrDefineStripePlaceholders() {
        Properties properties = loadProperties("application.yml");

        assertThat(properties).doesNotContainKey("spring.profiles.active");
        assertThat(properties).doesNotContainKeys(
                "stripe.api-key",
                "stripe.webhook-secret",
                "stripe.public-key");
    }

    @Test
    void devConfiguration_shouldKeepLocalStripePlaceholders() {
        Properties properties = loadProperties("application-dev.yml");

        assertThat(properties.getProperty("stripe.api-key")).isEqualTo("${STRIPE_SECRET_KEY:sk_test_placeholder}");
        assertThat(properties.getProperty("stripe.webhook-secret")).isEqualTo("${STRIPE_WEBHOOK_SECRET:whsec_placeholder}");
        assertThat(properties.getProperty("stripe.public-key")).isEqualTo("${STRIPE_PUBLIC_KEY:pk_test_placeholder}");
    }

    @Test
    void prodConfiguration_shouldRequireStripeEnvironmentVariables() {
        Properties properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("stripe.api-key")).isEqualTo("${STRIPE_SECRET_KEY}");
        assertThat(properties.getProperty("stripe.webhook-secret")).isEqualTo("${STRIPE_WEBHOOK_SECRET}");
        assertThat(properties.getProperty("stripe.public-key")).isEqualTo("${STRIPE_PUBLIC_KEY}");
    }

    @Test
    void prodConfiguration_shouldRequireDedicatedFlywayCredentialsAndRejectAutomaticBaselining() {
        Properties properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${DATABASE_USERNAME}");
        assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${DATABASE_PASSWORD}");
        assertThat(properties.getProperty("spring.flyway.url")).isEqualTo("${FLYWAY_URL:${DATABASE_URL}}");
        assertThat(properties.getProperty("spring.flyway.user")).isEqualTo("${FLYWAY_USER}");
        assertThat(properties.getProperty("spring.flyway.password")).isEqualTo("${FLYWAY_PASSWORD}");
        assertThat(properties.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false");
    }

    @Test
    void devConfiguration_shouldKeepExplicitLocalFlywayIdentityAndBaseliningCompatibility() {
        Properties properties = loadProperties("application-dev.yml");

        assertThat(properties.getProperty("spring.datasource.username"))
                .isEqualTo("${DATABASE_USERNAME:${APP_DB_USER:shop_dev}}");
        assertThat(properties.getProperty("spring.flyway.user"))
                .isEqualTo("${FLYWAY_USER:${POSTGRES_USER:postgres}}");
        assertThat(properties.getProperty("spring.flyway.password"))
                .isEqualTo("${FLYWAY_PASSWORD:${POSTGRES_PASSWORD:postgres}}");
        assertThat(properties.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("true");
    }

    @Test
    void prodStartup_shouldFailWhenFlywayUserIsMissing() {
        productionContext()
                .withPropertyValues("spring.flyway.user=${MISSING_TEST_FLYWAY_USER}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production Flyway username is required");
                });
    }

    @Test
    void prodStartup_shouldFailWhenFlywayPasswordIsMissingWithoutExposingSuppliedCredentials() {
        productionContext()
                .withPropertyValues(
                        "spring.flyway.user=dedicated_migration_user",
                        "spring.flyway.password=${MISSING_TEST_FLYWAY_PASSWORD}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production Flyway password is required")
                            .hasMessageNotContaining("dedicated_migration_user");
                });
    }

    @Test
    void prodStartup_shouldFailBeforeConnectingWhenFlywayAndRuntimeUsernamesMatch() {
        productionContext()
                .withPropertyValues(
                        "spring.flyway.user=runtime_user",
                        "spring.flyway.password=migration_password")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Production Flyway and runtime database usernames must differ")
                            .hasMessageNotContaining("migration_password");
                });
    }

    @Test
    void prodStartup_shouldRejectBlankFlywayUsername() {
        productionContext()
                .withPropertyValues(
                        "spring.flyway.user= ",
                        "spring.flyway.password=migration_password")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Production Flyway username is required"));
    }

    @Test
    void prodStartup_shouldRejectBlankFlywayPassword() {
        productionContext()
                .withPropertyValues(
                        "spring.flyway.user=migration_user",
                        "spring.flyway.password= ")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Production Flyway password is required"));
    }

    @Test
    void prodStartup_shouldRejectBlankRuntimeUsername() {
        productionContext()
                .withPropertyValues(
                        "spring.datasource.username= ",
                        "spring.flyway.user=migration_user",
                        "spring.flyway.password=migration_password")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Production Flyway runtime username is required"));
    }

    private static ApplicationContextRunner productionContext() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(ProductionFlywayIdentityConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        TEST_DATABASE_PROPERTIES,
                        "DATABASE_USERNAME=runtime_user",
                        "DATABASE_PASSWORD=runtime_password");
    }

    private static Properties loadProperties(String resourceName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resourceName));
        return factory.getObject();
    }
}
