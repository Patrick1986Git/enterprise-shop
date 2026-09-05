package com.company.shop.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class JwtProductionConfigurationTest {

    private static final String VALID_SECRET = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=";
    private static final String DISCLOSURE_MARKER = "must-not-appear-in-diagnostics!";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "security.jwt.secret=" + VALID_SECRET);

    @Test
    void productionJwtConfiguration_shouldStartWithValidContract() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtTokenProvider.class);
            assertThat(context.getBean(JwtProperties.class).getExpiration()).isEqualTo(3_600_000L);
        });
    }

    @Test
    void productionJwtConfiguration_shouldFailForMissingOrBlankSecret() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("security.jwt.secret= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionJwtConfiguration_shouldFailForMalformedOrWeakSecretWithoutDisclosure() {
        assertFailureDoesNotDisclose(DISCLOSURE_MARKER);
        assertFailureDoesNotDisclose("d2Vhaw==");
    }

    @Test
    void productionJwtConfiguration_shouldFailForInvalidExpiration() {
        contextRunner.withPropertyValues("security.jwt.expiration=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("security.jwt.expiration=-1")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("security.jwt.expiration=" + Long.MAX_VALUE)
                .run(context -> assertThat(context).hasFailed());
    }

    private void assertFailureDoesNotDisclose(String secret) {
        contextRunner.withPropertyValues("security.jwt.secret=" + secret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageNotContaining(secret);
                    assertThat(rootCause(context.getStartupFailure())).hasMessageNotContaining(secret);
                });
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    @Import(JwtTokenProvider.class)
    static class TestConfiguration {
    }
}
