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
    private static final String PREVIOUS_SECRET = "cHJldmlvdXMta2V5LW1hdGVyaWFsLTMyLWJ5dGVzISE=";
    private static final String DISCLOSURE_MARKER = "must-not-appear-in-diagnostics!";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "spring.profiles.active=prod",
                    "security.jwt.key-id=current-2026-09",
                    "security.jwt.secret=" + VALID_SECRET);

    @Test
    void productionJwtConfiguration_shouldStartWithValidContract() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JwtTokenProvider.class);
            assertThat(context.getBean(JwtProperties.class).getKeyId()).isEqualTo("current-2026-09");
            assertThat(context.getBean(JwtProperties.class).getExpiration()).isEqualTo(3_600_000L);
        });
    }

    @Test
    void productionJwtConfiguration_shouldStartWithBoundedPreviousKey() {
        contextRunner.withPropertyValues(
                        "security.jwt.previous-key-id=previous-2026-08",
                        "security.jwt.previous-secret=" + PREVIOUS_SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionJwtConfiguration_shouldFailForMissingOrBlankSecretOrKeyId() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "security.jwt.key-id=current-2026-09")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("security.jwt.secret= ")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("security.jwt.key-id= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionJwtConfiguration_shouldFailForMalformedOrWeakSecretWithoutDisclosure() {
        assertFailureDoesNotDisclose("security.jwt.secret", DISCLOSURE_MARKER);
        assertFailureDoesNotDisclose("security.jwt.secret", "d2Vhaw==");
    }

    @Test
    void productionJwtConfiguration_shouldFailForIncompleteDuplicateOrUnsafePreviousKeyConfiguration() {
        contextRunner.withPropertyValues("security.jwt.previous-key-id=previous-2026-08")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "security.jwt.previous-key-id=current-2026-09",
                        "security.jwt.previous-secret=" + PREVIOUS_SECRET)
                .run(context -> assertThat(context).hasFailed());
        assertPreviousFailureDoesNotDisclose(DISCLOSURE_MARKER);
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

    private void assertFailureDoesNotDisclose(String property, String secret) {
        contextRunner.withPropertyValues(property + "=" + secret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageNotContaining(secret);
                    assertThat(rootCause(context.getStartupFailure())).hasMessageNotContaining(secret);
                });
    }

    private void assertPreviousFailureDoesNotDisclose(String secret) {
        contextRunner.withPropertyValues(
                        "security.jwt.previous-key-id=previous-2026-08",
                        "security.jwt.previous-secret=" + secret)
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
