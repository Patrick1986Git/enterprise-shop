package com.company.shop.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.micrometer.core.instrument.MeterRegistry;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.config.SecurityConfig;
import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, UserDetailsServiceImpl.class,
        AuthServiceImpl.class, EmailNormalizer.class, GlobalExceptionHandler.class,
        MessageService.class, TestMeterRegistryConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class AuthLoginSecurityContractWebMvcTest {

    private static final String PASSWORD = "StrongPassword123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        User active = user("active@example.com", true);
        User disabled = user("disabled@example.com", false);
        when(userRepository.findActiveByEmailWithRoles(anyString())).thenAnswer(invocation -> {
            String email = invocation.getArgument(0);
            if (active.getEmail().equals(email)) {
                return Optional.of(active);
            }
            if (disabled.getEmail().equals(email)) {
                return Optional.of(disabled);
            }
            return Optional.empty();
        });
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
        when(jwtTokenProvider.generateToken(any())).thenReturn("jwt-value");
    }

    @Test
    void login_shouldIssueBearerTokenForCorrectCredentials() throws Exception {
        mockMvc.perform(login("active@example.com", PASSWORD, "login-success"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Request-Id", "login-success"))
                .andExpect(jsonPath("$.token").value("jwt-value"))
                .andExpect(jsonPath("$.type").value("Bearer"));
    }

    @Test
    void login_shouldReturnEquivalentGenericUnauthorizedForAuthenticationFailures(CapturedOutput output) throws Exception {
        double countBefore = invalidCredentialsCount();
        String wrongPasswordBody = assertUnauthorized("active@example.com", "WrongPassword123!", "login-wrong");
        String unknownBody = assertUnauthorized("unknown@example.com", PASSWORD, "login-unknown");
        String deletedBody = assertUnauthorized("deleted@example.com", PASSWORD, "login-deleted");
        String disabledBody = assertUnauthorized("disabled@example.com", PASSWORD, "login-disabled");

        assertThat(errorContract(wrongPasswordBody))
                .isEqualTo(errorContract(unknownBody))
                .isEqualTo(errorContract(deletedBody))
                .isEqualTo(errorContract(disabledBody));
        assertThat(output).doesNotContain("BadCredentialsException", "UsernameNotFoundException", PASSWORD,
                "WrongPassword123!", "jwt-value");
        assertThat(invalidCredentialsCount()).isEqualTo(countBefore + 4);
    }

    @Test
    void login_shouldRejectMalformedAndBlankCredentialsBeforeAuthentication() throws Exception {
        mockMvc.perform(login("not-email", PASSWORD, "login-malformed"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Request-Id", "login-malformed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.email").isArray());

        mockMvc.perform(login("active@example.com", "", "login-blank"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Request-Id", "login-blank"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").isArray());
    }

    @Test
    void login_shouldRejectOverlongCredentialsBeforeAuthentication() throws Exception {
        mockMvc.perform(login("a".repeat(244) + "@example.com", PASSWORD, "login-long-email"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "login-long-email"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.email").isArray());

        mockMvc.perform(login("active@example.com", "x".repeat(73), "login-long-password"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "login-long-password"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").isArray());

        mockMvc.perform(login("active@example.com", "🔐".repeat(19), "login-long-unicode-password"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "login-long-unicode-password"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").isArray());
    }

    private String assertUnauthorized(String email, String password, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(login(email, password, requestId))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.errorCode").value("USER_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.timestamp").exists())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String errorContract(String body) throws Exception {
        var json = objectMapper.readTree(body);
        return json.get("status") + "|" + json.get("message") + "|" + json.get("errorCode") + "|" + json.get("errors");
    }

    private double invalidCredentialsCount() {
        var counter = meterRegistry.find("shop.business_exception.total")
                .tags("error_code", "USER_INVALID_CREDENTIALS", "status_class", "4xx")
                .counter();
        return counter != null ? counter.count() : 0;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String email, String password, String requestId) throws Exception {
        return post("/api/v1/auth/login")
                .with(csrf())
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new Login(email, password)));
    }

    private User user(String email, boolean enabled) {
        User user = new User(email, passwordEncoder.encode(PASSWORD), "First", "Last");
        user.addRole(new Role(SecurityConstants.ROLE_USER));
        if (!enabled) {
            user.disable();
        }
        return user;
    }

    private record Login(String email, String password) {
    }
}
