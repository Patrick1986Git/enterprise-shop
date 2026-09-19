package com.company.shop.module.user.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.support.WebMvcSliceTestConfig;
import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;

@WebMvcTest(controllers = UserController.class)
@Import(WebMvcSliceTestConfig.class)
class UserControllerWebMvcTest {

    private static final String CURRENT_USER_URL = "/api/v1/me";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Nested
    class GetCurrentUser {

        @Test
        void getCurrentUser_shouldReturnForbiddenForAnonymous() throws Exception {
            mockMvc.perform(get(CURRENT_USER_URL))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void getCurrentUser_shouldReturnOkAndDelegateToServiceForAuthenticatedUser() throws Exception {
            UserResponseDTO response = new UserResponseDTO(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    "john.doe@example.com",
                    "John",
                    "Doe",
                    true, Set.of("ROLE_USER"));
            when(userService.getCurrentUserProfile()).thenReturn(response);

            mockMvc.perform(get(CURRENT_USER_URL)
                            .with(user("john.doe@example.com").roles("USER")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.email").value("john.doe@example.com"))
                    .andExpect(jsonPath("$.firstName").value("John"))
                    .andExpect(jsonPath("$.lastName").value("Doe"))
                    .andExpect(jsonPath("$.roles").isArray())
                    .andExpect(jsonPath("$.roles.length()").value(1))
                    .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_USER")));

            verify(userService).getCurrentUserProfile();
            verifyNoMoreInteractions(userService);
        }
    }

    @Nested
    class ChangePassword {

        @Test
        void changePassword_shouldRequireAuthentication() throws Exception {
            mockMvc.perform(put(CURRENT_USER_URL + "/password").with(csrf())
                            .contentType(APPLICATION_JSON)
                            .content(passwordChangeJson("OldPassword123!", "NewPassword456!")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void changePassword_shouldReturnNoContentAndDelegateForValidRequest() throws Exception {
            mockMvc.perform(put(CURRENT_USER_URL + "/password")
                            .with(csrf()).with(user("john.doe@example.com").roles("USER"))
                            .contentType(APPLICATION_JSON)
                            .content(passwordChangeJson("OldPassword123!", "NewPassword456!")))
                    .andExpect(status().isNoContent());

            verify(userService).changeCurrentUserPassword(any());
        }

        @Test
        void changePassword_shouldRejectMismatchedOrOverlongUtf8PasswordBeforeService() throws Exception {
            mockMvc.perform(put(CURRENT_USER_URL + "/password")
                            .with(csrf()).with(user("john.doe@example.com").roles("USER"))
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"currentPassword":"OldPassword123!","newPassword":"%s",
                                     "newPasswordRepeat":"different-password"}
                                    """.formatted("🔐".repeat(19))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.newPassword").isArray())
                    .andExpect(jsonPath("$.errors.newPasswordRepeat").isArray());

            verifyNoInteractions(userService);
        }

        private String passwordChangeJson(String currentPassword, String newPassword) {
            return """
                    {"currentPassword":"%s","newPassword":"%s","newPasswordRepeat":"%s"}
                    """.formatted(currentPassword, newPassword, newPassword);
        }
    }
}
