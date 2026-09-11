package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;

@SpringBootTest(
        classes = ActuatorSecurityTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "management.endpoint.prometheus.enabled=true",
                "management.prometheus.metrics.export.enabled=true",
                "server.tomcat.threads.max=8",
                "server.tomcat.max-connections=32",
                "server.tomcat.accept-count=8",
                "server.tomcat.connection-timeout=5s",
                "spring.datasource.password=SENTINEL_DATABASE_PASSWORD",
                "spring.flyway.password=SENTINEL_FLYWAY_PASSWORD",
                "security.jwt.secret=SENTINEL_JWT_SECRET",
                "security.jwt.previous-secret=SENTINEL_PREVIOUS_JWT_SECRET",
                "stripe.api-key=SENTINEL_STRIPE_SECRET",
                "stripe.webhook-secret=SENTINEL_STRIPE_WEBHOOK_SECRET",
                "spring.mail.password=SENTINEL_SMTP_PASSWORD",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ActuatorSecurityTest {

    private static final String[] SECRET_SENTINELS = {
            "SENTINEL_DATABASE_PASSWORD", "SENTINEL_FLYWAY_PASSWORD", "SENTINEL_JWT_SECRET",
            "SENTINEL_PREVIOUS_JWT_SECRET", "SENTINEL_STRIPE_SECRET",
            "SENTINEL_STRIPE_WEBHOOK_SECRET", "SENTINEL_SMTP_PASSWORD"
    };

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void actuatorHealth_shouldReturnOkForAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}", true))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(result -> assertNoSecrets(result.getResponse().getContentAsString()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/health/liveness", "/actuator/health/readiness" })
    void actuatorAvailabilityProbes_shouldReturnOkForAnonymous(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", true))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(result -> assertNoSecrets(result.getResponse().getContentAsString()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/info", "/actuator/metrics", "/actuator/prometheus" })
    void actuatorPrivilegedEndpoints_shouldDenyAnonymous(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/info", "/actuator/metrics", "/actuator/prometheus" })
    void actuatorPrivilegedEndpoints_shouldDenyRoleUser(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint).with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/info", "/actuator/metrics", "/actuator/prometheus" })
    void actuatorPrivilegedEndpoints_shouldAllowRoleAdmin(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/env", "/actuator/configprops", "/actuator/beans", "/actuator/mappings",
            "/actuator/heapdump", "/actuator/threaddump", "/actuator/logfile"
    })
    void actuatorDangerousIntrospectionEndpoints_shouldNotBeExposed(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertNoSecrets(result.getResponse().getContentAsString()));
    }

    @Test
    void unknownActuatorPath_shouldNotBypassAuthenticationOrBecomeExposed() throws Exception {
        mockMvc.perform(get("/actuator/not-an-endpoint"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/not-an-endpoint").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    private static void assertNoSecrets(String responseBody) {
        assertThat(responseBody).doesNotContain(SECRET_SENTINELS);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({ SecurityConfig.class, JwtAuthenticationFilter.class })
    static class TestApplication {

        @Bean
        PrometheusMeterRegistry prometheusMeterRegistry() {
            return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        }

        @Bean
        HealthIndicator dbHealthContributor() {
            return () -> Health.up().build();
        }

    }
}
