package com.company.shop.module.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.config.SecurityConfig;
import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.dto.UserUpdateDTO;
import com.company.shop.module.user.exception.UserNotFoundException;
import com.company.shop.module.user.service.UserService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AdminUserController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, MessageService.class, TestMeterRegistryConfig.class })
class AdminUserControllerWebMvcTest {

    private static final String ADMIN_USERS_URL = "/api/v1/admin/users";
    private static final String ADMIN_USER_BY_ID_URL = "/api/v1/admin/users/{id}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    class GetUsers {

        @Test
        void getUsers_shouldReturnForbiddenForAnonymous() throws Exception {
            mockMvc.perform(get(ADMIN_USERS_URL))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void getUsers_shouldReturnForbiddenForUserRole() throws Exception {
            mockMvc.perform(get(ADMIN_USERS_URL)
                            .with(user("user").roles("USER")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void getUsers_shouldReturnOkForAdminAndReturnPageOfUsers() throws Exception {
            UserResponseDTO first = sampleUser(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    "john.doe@example.com",
                    "John",
                    "Doe",
                    Set.of("ROLE_USER"));

            UserResponseDTO second = sampleUser(
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "admin@example.com",
                    "Alice",
                    "Admin",
                    Set.of("ROLE_ADMIN", "ROLE_USER"));

            when(userService.findAll(any())).thenReturn(
                    new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2));

            mockMvc.perform(get(ADMIN_USERS_URL)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].id").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.content[0].email").value("john.doe@example.com"))
                    .andExpect(jsonPath("$.content[0].firstName").value("John"))
                    .andExpect(jsonPath("$.content[0].lastName").value("Doe"))
                    .andExpect(jsonPath("$.content[0].roles.length()").value(1))
                    .andExpect(jsonPath("$.content[0].roles", containsInAnyOrder("ROLE_USER")))
                    .andExpect(jsonPath("$.content[1].id").value("22222222-2222-2222-2222-222222222222"))
                    .andExpect(jsonPath("$.content[1].roles.length()").value(2))
                    .andExpect(jsonPath("$.content[1].roles", containsInAnyOrder("ROLE_ADMIN", "ROLE_USER")))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.totalElements").value(2));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(userService).findAll(pageableCaptor.capture());
            verifyNoMoreInteractions(userService);

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(20);
        }

        @Test
        void getUsers_shouldPassCustomPageableForAdmin() throws Exception {
            when(userService.findAll(any())).thenReturn(
                    new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

            mockMvc.perform(get(ADMIN_USERS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .param("page", "2")
                            .param("size", "5")
                            .param("sort", "lastName,desc"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.size").value(5))
                    .andExpect(jsonPath("$.number").value(2));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(userService).findAll(pageableCaptor.capture());
            verifyNoMoreInteractions(userService);

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(2);
            assertThat(captured.getPageSize()).isEqualTo(5);
            assertThat(captured.getSort().isSorted()).isTrue();
            assertThat(captured.getSort().getOrderFor("lastName")).isNotNull();
            assertThat(captured.getSort().getOrderFor("lastName").getDirection()).isEqualTo(Sort.Direction.DESC);
        }
    }

    @Nested
    class GetUserById {

        @Test
        void getUserById_shouldReturnForbiddenForAnonymous() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(get(ADMIN_USER_BY_ID_URL, id))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void getUserById_shouldReturnForbiddenForUserRole() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(get(ADMIN_USER_BY_ID_URL, id)
                            .with(user("user").roles("USER")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void getUserById_shouldReturnOkForAdminAndDelegateToServiceWithExactId() throws Exception {
            UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
            UserResponseDTO response = sampleUser(
                    id,
                    "mark.smith@example.com",
                    "Mark",
                    "Smith",
                    Set.of("ROLE_USER"));
            when(userService.findById(eq(id))).thenReturn(response);

            mockMvc.perform(get(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("33333333-3333-3333-3333-333333333333"))
                    .andExpect(jsonPath("$.email").value("mark.smith@example.com"))
                    .andExpect(jsonPath("$.firstName").value("Mark"))
                    .andExpect(jsonPath("$.lastName").value("Smith"))
                    .andExpect(jsonPath("$.roles.length()").value(1))
                    .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_USER")));

            verify(userService).findById(eq(id));
            verifyNoMoreInteractions(userService);
        }

        @Test
        void getUserById_shouldReturnNotFoundApiErrorWhenUserMissing() throws Exception {
            UUID id = UUID.fromString("44444444-4444-4444-4444-444444444444");
            when(userService.findById(eq(id))).thenThrow(new UserNotFoundException());

            mockMvc.perform(get(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("User not found"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(userService).findById(eq(id));
            verifyNoMoreInteractions(userService);
        }

        @Test
        void getUserById_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            mockMvc.perform(get(ADMIN_USER_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: id"))
                    .andExpect(jsonPath("$.errors.parameter").value("id"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(userService);
        }
    }

    @Nested
    class UpdateUser {

        @Test
        void updateUser_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            UserUpdateDTO request = new UserUpdateDTO("John", "Doe");

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, UUID.randomUUID())
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void updateUser_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            UserUpdateDTO request = new UserUpdateDTO("John", "Doe");

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, UUID.randomUUID())
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void updateUser_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            UserUpdateDTO request = new UserUpdateDTO("John", "Doe");

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, UUID.randomUUID())
                            .with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void updateUser_shouldReturnOkForAdminAndPassExactDtoToService() throws Exception {
            UUID id = UUID.fromString("55555555-5555-5555-5555-555555555555");
            UserUpdateDTO request = new UserUpdateDTO("Anna", "Kowalska");
            UserResponseDTO response = sampleUser(
                    id,
                    "anna.kowalska@example.com",
                    "Anna",
                    "Kowalska",
                    Set.of("ROLE_USER"));
            when(userService.update(eq(id), any(UserUpdateDTO.class))).thenReturn(response);

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("55555555-5555-5555-5555-555555555555"))
                    .andExpect(jsonPath("$.email").value("anna.kowalska@example.com"))
                    .andExpect(jsonPath("$.firstName").value("Anna"))
                    .andExpect(jsonPath("$.lastName").value("Kowalska"))
                    .andExpect(jsonPath("$.roles.length()").value(1))
                    .andExpect(jsonPath("$.roles", containsInAnyOrder("ROLE_USER")));

            ArgumentCaptor<UserUpdateDTO> dtoCaptor = ArgumentCaptor.forClass(UserUpdateDTO.class);
            verify(userService).update(eq(id), dtoCaptor.capture());
            verifyNoMoreInteractions(userService);

            UserUpdateDTO captured = dtoCaptor.getValue();
            assertThat(captured.getFirstName()).isEqualTo("Anna");
            assertThat(captured.getLastName()).isEqualTo("Kowalska");
        }

        @Test
        void updateUser_shouldReturnBadRequestWhenFirstNameIsBlank() throws Exception {
            UUID id = UUID.randomUUID();
            String invalidBody = """
                    {
                      "firstName": "",
                      "lastName": "Nowak"
                    }
                    """;

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.firstName").isArray())
                    .andExpect(jsonPath("$.errors.firstName", not(empty())))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(userService);
        }

        @Test
        void updateUser_shouldReturnBadRequestWhenLastNameExceedsMaxLength() throws Exception {
            UUID id = UUID.randomUUID();
            String invalidBody = """
                    {
                      "firstName": "Jan",
                      "lastName": "%s"
                    }
                    """.formatted("A".repeat(101));

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.lastName").isArray())
                    .andExpect(jsonPath("$.errors.lastName", not(empty())))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(userService);
        }

        @Test
        void updateUser_shouldReturnBadRequestWhenLastNameIsBlank() throws Exception {
            UUID id = UUID.randomUUID();
            String invalidBody = """
                    {
                      "firstName": "Jan",
                      "lastName": ""
                    }
                    """;

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.lastName").isArray())
                    .andExpect(jsonPath("$.errors.lastName", not(empty())))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(userService);
        }

        @Test
        void updateUser_shouldReturnNotFoundApiErrorWhenUserMissing() throws Exception {
            UUID id = UUID.fromString("66666666-6666-6666-6666-666666666666");
            UserUpdateDTO request = new UserUpdateDTO("Jan", "Nowak");
            when(userService.update(eq(id), any(UserUpdateDTO.class))).thenThrow(new UserNotFoundException());

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("User not found"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(userService).update(eq(id), any(UserUpdateDTO.class));
            verifyNoMoreInteractions(userService);
        }

        @Test
        void updateUser_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            String body = """
                    {
                      "firstName": "Jan",
                      "lastName": "Nowak"
                    }
                    """;

            mockMvc.perform(put(ADMIN_USER_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: id"))
                    .andExpect(jsonPath("$.errors.parameter").value("id"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(userService);
        }
    }

    @Nested
    class DeleteUser {

        @Test
        void deleteUser_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_USER_BY_ID_URL, id)
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void deleteUser_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_USER_BY_ID_URL, id)
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void deleteUser_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(userService);
        }

        @Test
        void deleteUser_shouldReturnNoContentForAdmin() throws Exception {
            UUID id = UUID.fromString("77777777-7777-7777-7777-777777777777");

            mockMvc.perform(delete(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isNoContent());

            verify(userService).delete(eq(id));
            verifyNoMoreInteractions(userService);
        }

        @Test
        void deleteUser_shouldReturnNotFoundApiErrorWhenUserMissing() throws Exception {
            UUID id = UUID.fromString("88888888-8888-8888-8888-888888888888");
            doThrow(new UserNotFoundException()).when(userService).delete(eq(id));

            mockMvc.perform(delete(ADMIN_USER_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("User not found"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(userService).delete(eq(id));
            verifyNoMoreInteractions(userService);
        }

        @Test
        void deleteUser_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            mockMvc.perform(delete(ADMIN_USER_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: id"))
                    .andExpect(jsonPath("$.errors.parameter").value("id"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(userService);
        }
    }

    private UserResponseDTO sampleUser(UUID id, String email, String firstName, String lastName, Set<String> roles) {
        return new UserResponseDTO(id, email, firstName, lastName, roles);
    }
}