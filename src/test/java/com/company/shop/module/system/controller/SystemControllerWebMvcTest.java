package com.company.shop.module.system.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.config.SecurityConfig;
import com.company.shop.module.system.dto.ApplicationStatusDTO;
import com.company.shop.module.system.service.ApplicationStatusService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;

@WebMvcTest(controllers = SystemController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, MessageService.class, TestMeterRegistryConfig.class })
class SystemControllerWebMvcTest {

    private static final String SYSTEM_STATUS_URL = "/api/v1/system/status";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApplicationStatusService applicationStatusService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Nested
    class GetSystemStatus {

        @Test
        void getSystemStatus_shouldReturnForbiddenForAnonymous() throws Exception {
            mockMvc.perform(get(SYSTEM_STATUS_URL))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(applicationStatusService);
        }

        @Test
        void getSystemStatus_shouldReturnOkAndDelegateToServiceForAuthenticatedUser() throws Exception {
            ApplicationStatusDTO response = applicationStatusResponse();
            when(applicationStatusService.getApplicationStatus()).thenReturn(response);

            mockMvc.perform(get(SYSTEM_STATUS_URL)
                            .with(user("user").roles("USER")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.applicationName").value("Enterprise Shop"))
                    .andExpect(jsonPath("$.version").value("1.0.0-test"))
                    .andExpect(jsonPath("$.environment").value("test"))
                    .andExpect(jsonPath("$.serverTime").value("2026-01-01T12:00:00Z"))
                    .andExpect(jsonPath("$.activeProfiles").isArray())
                    .andExpect(jsonPath("$.activeProfiles.length()").value(1))
                    .andExpect(jsonPath("$.activeProfiles", containsInAnyOrder("test")))
                    .andExpect(jsonPath("$.javaVersion").value("17"))
                    .andExpect(jsonPath("$.osName").value("Linux"))
                    .andExpect(jsonPath("$.osVersion").value("6.0"))
                    .andExpect(jsonPath("$.hostName").value("localhost"));

            verify(applicationStatusService).getApplicationStatus();
            verifyNoMoreInteractions(applicationStatusService);
        }
    }

    private static ApplicationStatusDTO applicationStatusResponse() {
        return new ApplicationStatusDTO(
                "Enterprise Shop",
                "1.0.0-test",
                "test",
                Instant.parse("2026-01-01T12:00:00Z"),
                List.of("test"),
                "17",
                "Linux",
                "6.0",
                "localhost");
    }
}