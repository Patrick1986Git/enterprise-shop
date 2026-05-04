package com.company.shop.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;

@WebMvcTest(controllers = {})
@AutoConfigureMockMvc
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class })
@ImportAutoConfiguration({
        EndpointAutoConfiguration.class,
        WebEndpointAutoConfiguration.class,
        HealthContributorAutoConfiguration.class,
        InfoContributorAutoConfiguration.class,
        MetricsAutoConfiguration.class,
        MetricsEndpointAutoConfiguration.class,
        SimpleMetricsExportAutoConfiguration.class
})
class ActuatorSecurityTest {

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
                .andExpect(status().isOk());
    }

    @Test
    void actuatorInfo_shouldBeProtectedForAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorMetrics_shouldBeProtectedForAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorInfoAndMetrics_shouldRejectRoleUser() throws Exception {
        mockMvc.perform(get("/actuator/info").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/metrics").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorInfoAndMetrics_shouldAllowRoleAdmin() throws Exception {
        mockMvc.perform(get("/actuator/info").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}
