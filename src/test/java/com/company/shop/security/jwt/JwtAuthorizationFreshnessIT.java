package com.company.shop.security.jwt;

import static com.company.shop.security.SecurityConstants.ROLE_ADMIN;
import static com.company.shop.security.SecurityConstants.ROLE_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.module.user.entity.Role;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
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
    private JdbcTemplate jdbcTemplate;

    @Test
    void issuedAdminToken_shouldStopAuthorizingImmediatelyAfterAccountIsSoftDeleted() throws Exception {
        User admin = createUser("jwt-admin-" + UUID.randomUUID() + "@example.com", ROLE_ADMIN);
        String token = login(admin.getEmail());

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
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

    private record LoginRequest(String email, String password) {
    }
}
