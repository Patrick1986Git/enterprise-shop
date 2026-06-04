package com.company.shop.security;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.company.shop.support.TestMeterRegistryConfig;

@WebMvcTest(controllers = AuthController.class, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({ GlobalExceptionHandler.class, MessageService.class, TestMeterRegistryConfig.class })
class AuthControllerRegisterValidationContractWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @Test
    void registerValidationContract_shouldReturnCreatedWhenRequestIsValid() throws Exception {
        RegisterRequestDTO request = new RegisterRequestDTO("user@example.com", "secret123", "secret123", "John", "Doe");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(authService).register(any());
    }

    @Test
    void registerValidationContract_shouldReturnApiErrorPayloadWhenPasswordsDoNotMatch() throws Exception {
        RegisterRequestDTO request = new RegisterRequestDTO("user@example.com", "secret123", "different123", "John", "Doe");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.passwordRepeat", hasItem("Passwords do not match")))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(authService, never()).register(any());
    }

    @Test
    void registerValidationContract_shouldReturnAggregatedApiErrorPayloadForMultipleInvalidFields() throws Exception {
        RegisterRequestDTO request = new RegisterRequestDTO("invalid-email", "short", "different", "", "");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.email").isArray())
                .andExpect(jsonPath("$.errors.email", not(empty())))
                .andExpect(jsonPath("$.errors.password").isArray())
                .andExpect(jsonPath("$.errors.password", not(empty())))
                .andExpect(jsonPath("$.errors.passwordRepeat").isArray())
                .andExpect(jsonPath("$.errors.passwordRepeat", not(empty())))
                .andExpect(jsonPath("$.errors.firstName").isArray())
                .andExpect(jsonPath("$.errors.firstName", not(empty())))
                .andExpect(jsonPath("$.errors.lastName").isArray())
                .andExpect(jsonPath("$.errors.lastName", not(empty())))
                .andExpect(jsonPath("$.errors.passwordRepeat", hasItem("Passwords do not match")))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(authService, never()).register(any());
    }

    @Test
    void registerValidationContract_shouldReturnPolishValidationMessagesWhenAcceptLanguageIsPolish() throws Exception {
        RegisterRequestDTO request = new RegisterRequestDTO("user@example.com", "secret123", "different123", "John", "Doe");

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Accept-Language", "pl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Walidacja nie powiodła się"))
                .andExpect(jsonPath("$.errors.passwordRepeat", hasItem("Hasła nie są identyczne")));
    }

    @Test
    void registerValidationContract_shouldReturnAggregatedApiErrorPayloadForNullFieldValues() throws Exception {
        RegisterRequestDTO request = new RegisterRequestDTO(null, null, null, null, null);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.email").isArray())
                .andExpect(jsonPath("$.errors.email", not(empty())))
                .andExpect(jsonPath("$.errors.password").isArray())
                .andExpect(jsonPath("$.errors.password", not(empty())))
                .andExpect(jsonPath("$.errors.passwordRepeat").isArray())
                .andExpect(jsonPath("$.errors.passwordRepeat", not(empty())))
                .andExpect(jsonPath("$.errors.firstName").isArray())
                .andExpect(jsonPath("$.errors.firstName", not(empty())))
                .andExpect(jsonPath("$.errors.lastName").isArray())
                .andExpect(jsonPath("$.errors.lastName", not(empty())))
                .andExpect(jsonPath("$.errors.passwordRepeat", not(hasItem("Hasła nie są identyczne"))))
                .andExpect(jsonPath("$.timestamp").exists());

        verify(authService, never()).register(any());
    }
}
