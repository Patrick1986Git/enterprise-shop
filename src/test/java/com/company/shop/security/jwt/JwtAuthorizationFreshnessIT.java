package com.company.shop.security.jwt;

import static com.company.shop.security.SecurityConstants.ROLE_ADMIN;
import static com.company.shop.security.SecurityConstants.ROLE_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.dto.RegisterRequestDTO;
import com.company.shop.module.user.exception.UserAlreadyExistsException;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.security.AuthService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtAuthorizationFreshnessIT extends PostgresContainerSupport {

    private static final String PASSWORD = "StrongPassword123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void issuedAdminToken_shouldStopAuthorizingImmediatelyAfterAccountIsSoftDeleted() throws Exception {
        User admin = createUser("jwt-admin-" + UUID.randomUUID() + "@example.com", ROLE_ADMIN);
        String token = login(admin.getEmail());

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/users/{id}", admin.getId())
                        .with(csrf())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        Boolean deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM users WHERE id = ?", Boolean.class, admin.getId());
        assertThat(deleted).isTrue();
        assertThat(userRepository.findActiveByEmailWithRoles(admin.getEmail())).isEmpty();

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM carts WHERE user_id = ?", Long.class, admin.getId())).isOne();
    }

    @Test
    void currentPersistedAuthorities_shouldControlAdminAccess() throws Exception {
        User admin = createUser("jwt-current-admin-" + UUID.randomUUID() + "@example.com", ROLE_ADMIN);
        User ordinaryUser = createUser("jwt-user-" + UUID.randomUUID() + "@example.com", ROLE_USER);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + login(admin.getEmail())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + login(ordinaryUser.getEmail())))
                .andExpect(status().isForbidden());
    }

    @Test
    void retiredEmail_shouldRemainReservedAndIssuedTokenShouldRemainUnauthenticated() throws Exception {
        String email = "jwt-retired-" + UUID.randomUUID() + "@example.com";
        register(email);
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, email);
        String issuedToken = login(email);
        assertThat(tokenProvider.getUsername(issuedToken)).isEqualTo(email);

        mockMvc.perform(get("/api/v1/me/cart").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isOk());

        User admin = createUser("jwt-retirement-admin-" + UUID.randomUUID() + "@example.com", ROLE_ADMIN);
        mockMvc.perform(delete("/api/v1/admin/users/{id}", userId)
                        .with(csrf())
                        .header("Authorization", "Bearer " + login(admin.getEmail())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/cart").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isForbidden());

        assertThatThrownBy(() -> authService.register(new RegisterRequestDTO(
                "  " + email.toUpperCase() + "  ", PASSWORD, PASSWORD, "JWT", "Replacement")))
                .isInstanceOf(UserAlreadyExistsException.class)
                .hasMessage("User account already exists");

        String conflictBody = mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email.toUpperCase(), PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("User account already exists"))
                .andReturn().getResponse().getContentAsString();

        assertThat(conflictBody).doesNotContain(
                "ux_users_email_lower", "users_email_key", "SQL", "JDBC", "Hibernate", userId.toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE lower(email) = lower(?)", Long.class, email)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE lower(email) = lower(?)", UUID.class, email)).isEqualTo(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM users WHERE id = ?", Boolean.class, userId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_roles WHERE user_id = ?", Long.class, userId)).isOne();

        mockMvc.perform(get("/api/v1/me/cart").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("USER_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    private User createUser(String email, String roleName) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        User user = new User(email, passwordEncoder.encode(PASSWORD), "JWT", "Test");
        user.addRole(role);
        return userRepository.saveAndFlush(user);
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("token").asText();
    }

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, PASSWORD))))
                .andExpect(status().isCreated());
    }

    private record LoginRequest(String email, String password) {
    }

    private record RegisterRequest(String email, String password, String passwordRepeat,
                                   String firstName, String lastName) {
        private RegisterRequest(String email, String password) {
            this(email, password, password, "JWT", "Retirement");
        }
    }
}
