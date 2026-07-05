package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ApplicationConfigurationProfileTest {

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

    private static Properties loadProperties(String resourceName) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resourceName));
        return factory.getObject();
    }
}
