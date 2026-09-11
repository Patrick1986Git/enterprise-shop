package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.web.ErrorProperties.IncludeAttribute;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Configuration;
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
    void prodConfiguration_shouldNotTrustForwardedHeaders() {
        Properties properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    @Test
    void prodConfiguration_shouldRequireDeploymentOwnedTomcatAdmissionCapacity() {
        Properties properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("server.tomcat.threads.max"))
                .isEqualTo("${SERVER_TOMCAT_THREADS_MAX}");
        assertThat(properties.getProperty("server.tomcat.max-connections"))
                .isEqualTo("${SERVER_TOMCAT_MAX_CONNECTIONS}");
        assertThat(properties.getProperty("server.tomcat.accept-count"))
                .isEqualTo("${SERVER_TOMCAT_ACCEPT_COUNT}");
        assertThat(properties.getProperty("server.tomcat.connection-timeout"))
                .isEqualTo("${SERVER_TOMCAT_CONNECTION_TIMEOUT}");
    }

    @Test
    void baseConfiguration_shouldDefineBoundedGracefulShutdown() {
        Properties properties = loadProperties("application.yml");

        assertThat(properties.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(properties.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
    }

    @Test
    void composeConfiguration_shouldAllowBothBlockingLifecyclePhasesToFinish() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertThat(compose).contains("stop_grace_period: 65s");
    }

    @Test
    void prodConfiguration_shouldSeparateLivenessFromDatabaseBackedReadiness() {
        Properties properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
        assertThat(properties.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
    }

    @Test
    void prodConfiguration_shouldExplicitlyOwnItsActuatorHttpAllowlist() {
        Properties properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,metrics,prometheus");
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
    void prodConfiguration_shouldRequireDeploymentOwnedHikariCapacityAndAcquisitionTimeout() {
        Properties properties = loadProperties("application-prod.yml");

        assertThat(properties.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .isEqualTo("${DATABASE_MAXIMUM_POOL_SIZE}");
        assertThat(properties.getProperty("spring.datasource.hikari.minimum-idle"))
                .isEqualTo("${DATABASE_MINIMUM_IDLE}");
        assertThat(properties.getProperty("spring.datasource.hikari.connection-timeout"))
                .isEqualTo("${DATABASE_CONNECTION_TIMEOUT_MILLISECONDS}");
    }

    @Test
    void prodConfiguration_shouldUseSafeSpringBootFallbackErrorDefaults() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(WebErrorPropertiesConfiguration.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    WebProperties webProperties = context.getBean(WebProperties.class);

                    assertThat(webProperties.getError().getIncludeMessage()).isEqualTo(IncludeAttribute.NEVER);
                    assertThat(webProperties.getError().getIncludeBindingErrors()).isEqualTo(IncludeAttribute.NEVER);
                    assertThat(webProperties.getError().getIncludeStacktrace()).isEqualTo(IncludeAttribute.NEVER);
                    assertThat(webProperties.getError().isIncludeException()).isFalse();
                    assertThat(webProperties.getError().getIncludePath()).isEqualTo(IncludeAttribute.ALWAYS);
                });
    }

    @Test
    void devAndTestConfigurations_shouldRetainSpringBootFallbackErrorDefaults() {
        for (String profile : new String[] { "dev", "test" }) {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(WebErrorPropertiesConfiguration.class)
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> {
                        WebProperties webProperties = context.getBean(WebProperties.class);

                        assertThat(webProperties.getError().getIncludeMessage()).isEqualTo(IncludeAttribute.NEVER);
                        assertThat(webProperties.getError().getIncludeBindingErrors()).isEqualTo(IncludeAttribute.NEVER);
                        assertThat(webProperties.getError().getIncludeStacktrace()).isEqualTo(IncludeAttribute.NEVER);
                        assertThat(webProperties.getError().isIncludeException()).isFalse();
                    });
        }
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
                        "DATABASE_PASSWORD=runtime_password",
                        "DATABASE_MAXIMUM_POOL_SIZE=4",
                        "DATABASE_MINIMUM_IDLE=0",
                        "DATABASE_CONNECTION_TIMEOUT_MILLISECONDS=1000");
    }

    private static Properties loadProperties(String resourceName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resourceName));
        return factory.getObject();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebProperties.class)
    static class WebErrorPropertiesConfiguration {
    }
}
