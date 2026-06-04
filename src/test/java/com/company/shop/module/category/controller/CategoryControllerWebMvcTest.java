package com.company.shop.module.category.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.support.WebMvcSliceTestConfig;
import com.company.shop.module.category.dto.CategoryResponseDTO;
import com.company.shop.module.category.exception.CategoryNotFoundException;
import com.company.shop.module.category.service.CategoryService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;

@WebMvcTest(controllers = CategoryController.class)
@Import(WebMvcSliceTestConfig.class)
class CategoryControllerWebMvcTest {

    private static final String CATEGORIES_URL = "/api/v1/categories";
    private static final String CATEGORY_BY_SLUG_URL = "/api/v1/categories/slug/{slug}";

    @Autowired
    private MockMvc mockMvc;

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
    class GetCategories {

        @Test
        void getCategories_shouldAllowAnonymousAndReturnPagedResponseWithDefaultPageable() throws Exception {
            CategoryResponseDTO category = sampleCategory(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    "Laptops",
                    "laptops",
                    "All laptops",
                    null);
            Page<CategoryResponseDTO> response = new PageImpl<>(List.of(category), PageRequest.of(0, 20), 1);
            when(categoryService.findAll(any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(CATEGORIES_URL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content", not(empty())))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.content[0].name").value("Laptops"))
                    .andExpect(jsonPath("$.content[0].slug").value("laptops"))
                    .andExpect(jsonPath("$.content[0].description").value("All laptops"))
                    .andExpect(jsonPath("$.content[0].parentName").doesNotExist())
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.numberOfElements").value(1))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(true))
                    .andExpect(jsonPath("$.empty").value(false));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(categoryService).findAll(pageableCaptor.capture());

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(20);
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void getCategories_shouldUseExplicitPaginationParameters() throws Exception {
            CategoryResponseDTO category = sampleCategory(
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    "Phones",
                    "phones",
                    "All phones",
                    null);
            Page<CategoryResponseDTO> response = new PageImpl<>(List.of(category), PageRequest.of(1, 5), 6);
            when(categoryService.findAll(any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(CATEGORIES_URL).param("page", "1").param("size", "5"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value("22222222-2222-2222-2222-222222222222"))
                    .andExpect(jsonPath("$.content[0].name").value("Phones"))
                    .andExpect(jsonPath("$.number").value(1))
                    .andExpect(jsonPath("$.size").value(5))
                    .andExpect(jsonPath("$.numberOfElements").value(1))
                    .andExpect(jsonPath("$.totalElements").value(6))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.first").value(false))
                    .andExpect(jsonPath("$.last").value(true))
                    .andExpect(jsonPath("$.empty").value(false));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(categoryService).findAll(pageableCaptor.capture());

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(1);
            assertThat(captured.getPageSize()).isEqualTo(5);
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void getCategories_shouldReturnEmptyPageContractWhenNoDataExists() throws Exception {
            Page<CategoryResponseDTO> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
            when(categoryService.findAll(any(Pageable.class))).thenReturn(emptyPage);

            mockMvc.perform(get(CATEGORIES_URL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content", empty()))
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.numberOfElements").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0))
                    .andExpect(jsonPath("$.first").value(true))
                    .andExpect(jsonPath("$.last").value(true))
                    .andExpect(jsonPath("$.empty").value(true));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(categoryService).findAll(pageableCaptor.capture());

            Pageable captured = pageableCaptor.getValue();
            assertThat(captured.getPageNumber()).isEqualTo(0);
            assertThat(captured.getPageSize()).isEqualTo(20);
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void getCategories_shouldReturnBadRequestWhenPageQueryParamIsNotANumber() throws Exception {
            mockMvc.perform(get(CATEGORIES_URL).param("page", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: page"))
                    .andExpect(jsonPath("$.errors.parameter").value("page"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void getCategories_shouldReturnBadRequestWhenSizeQueryParamIsNotANumber() throws Exception {
            mockMvc.perform(get(CATEGORIES_URL).param("size", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: size"))
                    .andExpect(jsonPath("$.errors.parameter").value("size"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoMoreInteractions(categoryService);
        }
    }

    @Nested
    class GetCategoryBySlug {

        @Test
        void getCategoryBySlug_shouldAllowAnonymousReturnOkAndDelegateToService() throws Exception {
            CategoryResponseDTO response = sampleCategory(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    "Gaming",
                    "gaming",
                    "Gaming devices",
                    "Electronics");
            when(categoryService.findBySlug("gaming")).thenReturn(response);

            mockMvc.perform(get(CATEGORY_BY_SLUG_URL, "gaming"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("33333333-3333-3333-3333-333333333333"))
                    .andExpect(jsonPath("$.name").value("Gaming"))
                    .andExpect(jsonPath("$.slug").value("gaming"))
                    .andExpect(jsonPath("$.description").value("Gaming devices"))
                    .andExpect(jsonPath("$.parentName").value("Electronics"));

            verify(categoryService).findBySlug("gaming");
            verifyNoMoreInteractions(categoryService);
        }

        @Test
        void getCategoryBySlug_shouldReturnNotFoundApiErrorWhenCategoryMissing() throws Exception {
            when(categoryService.findBySlug("missing-slug")).thenThrow(new CategoryNotFoundException("missing-slug"));

            mockMvc.perform(get(CATEGORY_BY_SLUG_URL, "missing-slug"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Category not found for slug: missing-slug"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(categoryService).findBySlug("missing-slug");
            verifyNoMoreInteractions(categoryService);
        }
    }

    private CategoryResponseDTO sampleCategory(UUID id, String name, String slug, String description, String parentName) {
        return new CategoryResponseDTO(id, name, slug, description, parentName);
    }
}