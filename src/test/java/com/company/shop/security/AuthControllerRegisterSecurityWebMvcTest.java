package com.company.shop.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.config.SecurityConfig;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AuthController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, TestMeterRegistryConfig.class, MessageService.class })
class AuthControllerRegisterSecurityWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Test
    void registerSecurityContract_shouldRejectDisallowedOriginBeforeService() throws Exception {
        RegisterRequestDTO request = new RegisterRequestDTO("user@example.com", "secret123", "secret123", "John", "Doe");

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Origin", "https://untrusted.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(authService, never()).register(any());
    }

    @Test
    void registerSecurityContract_shouldAllowAnonymousRequestWithoutCsrfAndCallService() throws Exception {
        RegisterRequestDTO request = new RegisterRequestDTO("user@example.com", "secret123", "secret123", "John", "Doe");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(authService).register(any());
    }
}
