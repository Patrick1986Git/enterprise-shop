package com.company.shop.module.category.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.support.WebMvcSliceTestConfig;
import com.company.shop.module.category.dto.CategoryCreateDTO;
import com.company.shop.module.category.dto.CategoryResponseDTO;
import com.company.shop.module.category.exception.CategoryAlreadyExistsException;
import com.company.shop.module.category.exception.CategoryHierarchyException;
import com.company.shop.module.category.exception.CategoryNotFoundException;
import com.company.shop.module.category.exception.CategorySlugAlreadyExistsException;
import com.company.shop.module.category.service.CategoryService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AdminCategoryController.class)
@Import(WebMvcSliceTestConfig.class)
class AdminCategoryControllerWebMvcTest {

    private static final String ADMIN_CATEGORIES_URL = "/api/v1/admin/categories";
    private static final String ADMIN_CATEGORY_BY_ID_URL = "/api/v1/admin/categories/{id}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Nested
    class GetCategoryById {

        @Test
        void getCategoryById_shouldReturnForbiddenForAnonymous() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(get(ADMIN_CATEGORY_BY_ID_URL, id))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).findById(eq(id));
        }

        @Test
        void getCategoryById_shouldReturnForbiddenForUserRole() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(get(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("user").roles("USER")))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).findById(eq(id));
        }

        @Test
        void getCategoryById_shouldReturnOkForAdminAndDelegateToServiceWithExactId() throws Exception {
            UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
            CategoryResponseDTO response = sampleCategory(id, "Laptopy", "laptopy", "Wszystkie laptopy", "Elektronika");
            when(categoryService.findById(eq(id))).thenReturn(response);

            mockMvc.perform(get(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.name").value("Laptopy"))
                    .andExpect(jsonPath("$.slug").value("laptopy"))
                    .andExpect(jsonPath("$.description").value("Wszystkie laptopy"))
                    .andExpect(jsonPath("$.parentName").value("Elektronika"));

            verify(categoryService).findById(eq(id));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void getCategoryById_shouldReturnNotFoundApiErrorWhenCategoryMissing() throws Exception {
            UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
            when(categoryService.findById(eq(id))).thenThrow(new CategoryNotFoundException(id));

            mockMvc.perform(get(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Category not found: 22222222-2222-2222-2222-222222222222"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).findById(eq(id));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void getCategoryById_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            mockMvc.perform(get(ADMIN_CATEGORY_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: id"))
                    .andExpect(jsonPath("$.errors.parameter").value("id"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoMoreInteractions(categoryService);
        }
    }

    @Nested
    class CreateCategory {

        @Test
        void createCategory_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Wszystkie laptopy", null);

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).create(any(CategoryCreateDTO.class));
        }

        @Test
        void createCategory_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Wszystkie laptopy", null);

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).create(any(CategoryCreateDTO.class));
        }

        @Test
        void createCategory_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Wszystkie laptopy", null);

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).create(any(CategoryCreateDTO.class));
        }

        @Test
        void createCategory_shouldReturnCreatedForAdminAndPassExactDtoToService() throws Exception {
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Wszystkie laptopy", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
            CategoryResponseDTO response = sampleCategory(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    "Laptopy",
                    "laptopy",
                    "Wszystkie laptopy",
                    "Elektronika");
            when(categoryService.create(any(CategoryCreateDTO.class))).thenReturn(response);

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("33333333-3333-3333-3333-333333333333"))
                    .andExpect(jsonPath("$.name").value("Laptopy"))
                    .andExpect(jsonPath("$.slug").value("laptopy"))
                    .andExpect(jsonPath("$.description").value("Wszystkie laptopy"))
                    .andExpect(jsonPath("$.parentName").value("Elektronika"));

            ArgumentCaptor<CategoryCreateDTO> dtoCaptor = ArgumentCaptor.forClass(CategoryCreateDTO.class);
            verify(categoryService).create(dtoCaptor.capture());
            verifyNoMoreInteractions(categoryService);

            CategoryCreateDTO captured = dtoCaptor.getValue();
            assertThat(captured.getName()).isEqualTo("Laptopy");
            assertThat(captured.getDescription()).isEqualTo("Wszystkie laptopy");
            assertThat(captured.getParentId()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        }

        @Test
        void createCategory_shouldReturnBadRequestWhenNameIsBlank() throws Exception {
            String invalidBody = """
                    {
                      "name": "",
                      "description": "Opis",
                      "parentId": null
                    }
                    """;

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.name").isArray())
                    .andExpect(jsonPath("$.errors.name", not(empty())))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService, never()).create(any(CategoryCreateDTO.class));
        }

        @Test
        void createCategory_shouldReturnBadRequestWhenNameExceedsMaxLength() throws Exception {
            String tooLongName = "a".repeat(151);
            String body = """
                    {
                      "name": "%s",
                      "description": "Opis",
                      "parentId": null
                    }
                    """.formatted(tooLongName);

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").isArray())
                    .andExpect(jsonPath("$.errors.name", not(empty())));

            verify(categoryService, never()).create(any(CategoryCreateDTO.class));
        }

        @Test
        void createCategory_shouldReturnBadRequestWhenDescriptionExceedsMaxLength() throws Exception {
            String tooLongDescription = "a".repeat(501);
            String body = """
                    {
                      "name": "Laptopy",
                      "description": "%s",
                      "parentId": null
                    }
                    """.formatted(tooLongDescription);

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.description").isArray())
                    .andExpect(jsonPath("$.errors.description", not(empty())));

            verify(categoryService, never()).create(any(CategoryCreateDTO.class));
        }

        @Test
        void createCategory_shouldReturnConflictWhenNameAlreadyExists() throws Exception {
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);
            when(categoryService.create(any(CategoryCreateDTO.class)))
                    .thenThrow(new CategoryAlreadyExistsException("Laptopy"));

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Category already exists with name: Laptopy"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            ArgumentCaptor<CategoryCreateDTO> dtoCaptor = ArgumentCaptor.forClass(CategoryCreateDTO.class);
            verify(categoryService).create(dtoCaptor.capture());
            verifyNoMoreInteractions(categoryService);
            assertThat(dtoCaptor.getValue().getName()).isEqualTo("Laptopy");
        }

        @Test
        void createCategory_shouldReturnConflictWhenSlugAlreadyExists() throws Exception {
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);
            when(categoryService.create(any(CategoryCreateDTO.class)))
                    .thenThrow(new CategorySlugAlreadyExistsException("laptopy"));

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_SLUG_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Category already exists with slug: laptopy"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).create(any(CategoryCreateDTO.class));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void createCategory_shouldReturnNotFoundWhenParentCategoryMissing() throws Exception {
            UUID parentId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", parentId);
            when(categoryService.create(any(CategoryCreateDTO.class)))
                    .thenThrow(new CategoryNotFoundException(parentId));

            mockMvc.perform(post(ADMIN_CATEGORIES_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Category not found: aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            ArgumentCaptor<CategoryCreateDTO> dtoCaptor = ArgumentCaptor.forClass(CategoryCreateDTO.class);
            verify(categoryService).create(dtoCaptor.capture());
            verifyNoMoreInteractions(categoryService);
            assertThat(dtoCaptor.getValue().getParentId()).isEqualTo(parentId);
        }
    }

    @Nested
    class UpdateCategory {

        @Test
        void updateCategory_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).update(eq(id), any(CategoryCreateDTO.class));
        }

        @Test
        void updateCategory_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).update(eq(id), any(CategoryCreateDTO.class));
        }

        @Test
        void updateCategory_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            UUID id = UUID.randomUUID();
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).update(eq(id), any(CategoryCreateDTO.class));
        }

        @Test
        void updateCategory_shouldReturnOkForAdminAndPassExactIdAndDtoToService() throws Exception {
            UUID id = UUID.fromString("44444444-4444-4444-4444-444444444444");
            UUID parentId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy Gaming", "Opis po zmianie", parentId);
            CategoryResponseDTO response = sampleCategory(id, "Laptopy Gaming", "laptopy-gaming", "Opis po zmianie", "Elektronika");
            when(categoryService.update(eq(id), any(CategoryCreateDTO.class))).thenReturn(response);

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("44444444-4444-4444-4444-444444444444"))
                    .andExpect(jsonPath("$.name").value("Laptopy Gaming"))
                    .andExpect(jsonPath("$.slug").value("laptopy-gaming"))
                    .andExpect(jsonPath("$.description").value("Opis po zmianie"))
                    .andExpect(jsonPath("$.parentName").value("Elektronika"));

            ArgumentCaptor<CategoryCreateDTO> dtoCaptor = ArgumentCaptor.forClass(CategoryCreateDTO.class);
            verify(categoryService).update(eq(id), dtoCaptor.capture());
            verifyNoMoreInteractions(categoryService);

            CategoryCreateDTO captured = dtoCaptor.getValue();
            assertThat(captured.getName()).isEqualTo("Laptopy Gaming");
            assertThat(captured.getDescription()).isEqualTo("Opis po zmianie");
            assertThat(captured.getParentId()).isEqualTo(parentId);
        }

        @Test
        void updateCategory_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: id"))
                    .andExpect(jsonPath("$.errors.parameter").value("id"));

            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void updateCategory_shouldReturnBadRequestWhenBodyValidationFails() throws Exception {
            UUID id = UUID.randomUUID();
            String invalidBody = """
                    {
                      "name": "",
                      "description": "Opis",
                      "parentId": null
                    }
                    """;

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").isArray())
                    .andExpect(jsonPath("$.errors.name", not(empty())));

            verify(categoryService, never()).update(eq(id), any(CategoryCreateDTO.class));
        }

        @Test
        void updateCategory_shouldReturnNotFoundWhenCategoryMissing() throws Exception {
            UUID id = UUID.fromString("55555555-5555-5555-5555-555555555555");
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);
            when(categoryService.update(eq(id), any(CategoryCreateDTO.class)))
                    .thenThrow(new CategoryNotFoundException(id));

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Category not found: 55555555-5555-5555-5555-555555555555"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).update(eq(id), any(CategoryCreateDTO.class));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void updateCategory_shouldReturnConflictWhenNameAlreadyExists() throws Exception {
            UUID id = UUID.randomUUID();
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);
            when(categoryService.update(eq(id), any(CategoryCreateDTO.class)))
                    .thenThrow(new CategoryAlreadyExistsException("Laptopy"));

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Category already exists with name: Laptopy"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).update(eq(id), any(CategoryCreateDTO.class));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void updateCategory_shouldReturnConflictWhenSlugAlreadyExists() throws Exception {
            UUID id = UUID.randomUUID();
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", null);
            when(categoryService.update(eq(id), any(CategoryCreateDTO.class)))
                    .thenThrow(new CategorySlugAlreadyExistsException("laptopy"));

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_SLUG_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").value("Category already exists with slug: laptopy"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).update(eq(id), any(CategoryCreateDTO.class));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void updateCategory_shouldReturnConflictWhenCategorySelfParentDetected() throws Exception {
            UUID id = UUID.randomUUID();
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", id);
            when(categoryService.update(eq(id), any(CategoryCreateDTO.class)))
                    .thenThrow(new CategoryHierarchyException("Category cannot be its own parent", "CATEGORY_SELF_PARENT"));

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_SELF_PARENT"))
                    .andExpect(jsonPath("$.message").value("Category cannot be its own parent"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).update(eq(id), any(CategoryCreateDTO.class));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void updateCategory_shouldReturnConflictWhenHierarchyCycleDetected() throws Exception {
            UUID id = UUID.randomUUID();
            CategoryCreateDTO request = new CategoryCreateDTO("Laptopy", "Opis", UUID.randomUUID());
            when(categoryService.update(eq(id), any(CategoryCreateDTO.class)))
                    .thenThrow(new CategoryHierarchyException("Category hierarchy cycle detected", "CATEGORY_CYCLE_DETECTED"));

            mockMvc.perform(put(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_CYCLE_DETECTED"))
                    .andExpect(jsonPath("$.message").value("Category hierarchy cycle detected"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).update(eq(id), any(CategoryCreateDTO.class));
            verifyNoMoreInteractions(categoryService);
        }
    }

    @Nested
    class DeleteCategory {

        @Test
        void deleteCategory_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).delete(eq(id));
        }

        @Test
        void deleteCategory_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).delete(eq(id));
        }

        @Test
        void deleteCategory_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isForbidden());

            verify(categoryService, never()).delete(eq(id));
        }

        @Test
        void deleteCategory_shouldReturnNoContentForAdminWhenCategoryExistsAndDelegateExactId() throws Exception {
            UUID id = UUID.fromString("77777777-7777-7777-7777-777777777777");

            mockMvc.perform(delete(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isNoContent());

            verify(categoryService).delete(eq(id));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void deleteCategory_shouldReturnNotFoundWhenCategoryMissing() throws Exception {
            UUID id = UUID.fromString("66666666-6666-6666-6666-666666666666");
            doThrow(new CategoryNotFoundException(id)).when(categoryService).delete(eq(id));

            mockMvc.perform(delete(ADMIN_CATEGORY_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Category not found: 66666666-6666-6666-6666-666666666666"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).delete(eq(id));
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void deleteCategory_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            mockMvc.perform(delete(ADMIN_CATEGORY_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: id"))
                    .andExpect(jsonPath("$.errors.parameter").value("id"));

            verifyNoMoreInteractions(categoryService);
        }
    }

    private CategoryResponseDTO sampleCategory(UUID id, String name, String slug, String description, String parentName) {
        return new CategoryResponseDTO(id, name, slug, description, parentName);
    }
}