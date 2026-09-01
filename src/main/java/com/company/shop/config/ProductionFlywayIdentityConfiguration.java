package com.company.shop.config;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionFlywayIdentityConfiguration {

    @Bean
    FlywayConfigurationCustomizer productionFlywayIdentityValidator(Environment environment) {
        String migrationUser = requiredCredential(environment, "spring.flyway.user", "username");
        requiredCredential(environment, "spring.flyway.password", "password");
        String runtimeUser = requiredCredential(environment, "spring.datasource.username", "runtime username");

        if (migrationUser.equals(runtimeUser)) {
            throw new IllegalStateException("Production Flyway and runtime database usernames must differ");
        }

        return configuration -> {
            // Validation is completed before Flyway constructs or opens its migration datasource.
        };
    }

    private static String requiredCredential(Environment environment, String property, String description) {
        String value;
        try {
            value = environment.getProperty(property);
        } catch (PlaceholderResolutionException exception) {
            throw new IllegalStateException("Production Flyway " + description + " is required");
        }
        if (!StringUtils.hasText(value) || value.startsWith("${")) {
            throw new IllegalStateException("Production Flyway " + description + " is required");
        }
        return value;
    }
}
