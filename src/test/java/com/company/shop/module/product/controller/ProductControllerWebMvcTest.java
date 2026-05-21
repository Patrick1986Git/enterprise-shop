package com.company.shop.module.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.common.i18n.MessageService;
import com.company.shop.config.SecurityConfig;
import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.dto.ProductSearchCriteria;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;

@WebMvcTest(controllers = ProductController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, MessageService.class, TestMeterRegistryConfig.class })
class ProductControllerWebMvcTest {

    private static final String PRODUCTS_URL = "/api/v1/products";
    private static final String PRODUCTS_BY_CATEGORY_URL = "/api/v1/products/category/{categoryId}";
    private static final String PRODUCT_BY_SLUG_URL = "/api/v1/products/slug/{slug}";
    private static final String SEARCH_PRODUCTS_URL = "/api/v1/products/search";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Nested
    class GetProducts {

        @Test
        void getProducts_shouldReturnPagedResponseWithDefaultPageable() throws Exception {
            Page<ProductResponseDTO> response = new PageImpl<>(
                    List.of(sampleProduct(UUID.fromString("11111111-1111-1111-1111-111111111111"), "Gaming Laptop", "gaming-laptop")),
                    PageRequest.of(0, 12),
                    1);
            when(productService.findAll(any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCTS_URL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.content[0].name").value("Gaming Laptop"))
                    .andExpect(jsonPath("$.content[0].price").value(199.99))
                    .andExpect(jsonPath("$.content[0].categoryName").value("Peripherals"))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productService).findAll(pageableCaptor.capture());

            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(0);
            assertThat(pageable.getPageSize()).isEqualTo(12);
        }

        @Test
        void getProducts_shouldPassCustomPageableAndSort() throws Exception {
            Page<ProductResponseDTO> response = new PageImpl<>(
                    List.of(sampleProduct(UUID.fromString("22222222-2222-2222-2222-222222222222"), "Mouse", "mouse")),
                    PageRequest.of(2, 5, Sort.by(Sort.Direction.ASC, "name")),
                    17);
            when(productService.findAll(any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCTS_URL)
                            .param("page", "2")
                            .param("size", "5")
                            .param("sort", "name,asc"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.number").value(2))
                    .andExpect(jsonPath("$.size").value(5))
                    .andExpect(jsonPath("$.totalElements").value(17))
                    .andExpect(jsonPath("$.totalPages").value(4));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productService).findAll(pageableCaptor.capture());

            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(5);
            assertThat(pageable.getSort().getOrderFor("name")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        void getProducts_shouldReturnEmptyPageWhenNoDataExists() throws Exception {
            Page<ProductResponseDTO> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 12), 0);
            when(productService.findAll(any(Pageable.class))).thenReturn(emptyPage);

            mockMvc.perform(get(PRODUCTS_URL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content", empty()))
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12))
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));

            verify(productService).findAll(any(Pageable.class));
        }

        @Test
        void getProducts_shouldReturnBadRequestWhenPageIsInvalidType() throws Exception {
            mockMvc.perform(get(PRODUCTS_URL).param("page", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: page"))
                    .andExpect(jsonPath("$.errors.parameter").value("page"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }

        @Test
        void getProducts_shouldReturnBadRequestWhenSizeIsInvalidType() throws Exception {
            mockMvc.perform(get(PRODUCTS_URL).param("size", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: size"))
                    .andExpect(jsonPath("$.errors.parameter").value("size"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }
    }

    @Nested
    class GetProductsByCategory {

        @Test
        void getProductsByCategory_shouldReturnPagedResponseWithDefaultPageable() throws Exception {
            UUID categoryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            Page<ProductResponseDTO> response = new PageImpl<>(
                    List.of(sampleProduct(UUID.fromString("33333333-3333-3333-3333-333333333333"), "Keyboard", "keyboard")),
                    PageRequest.of(0, 12),
                    1);
            when(productService.findAllByCategory(any(UUID.class), any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCTS_BY_CATEGORY_URL, categoryId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Keyboard"))
                    .andExpect(jsonPath("$.content[0].categoryName").value("Peripherals"))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1));

            ArgumentCaptor<UUID> categoryCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productService).findAllByCategory(categoryCaptor.capture(), pageableCaptor.capture());

            assertThat(categoryCaptor.getValue()).isEqualTo(categoryId);
            assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(12);
        }

        @Test
        void getProductsByCategory_shouldPassCustomPageableAndSort() throws Exception {
            UUID categoryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            Page<ProductResponseDTO> response = new PageImpl<>(
                    List.of(sampleProduct(UUID.fromString("44444444-4444-4444-4444-444444444444"), "Monitor", "monitor")),
                    PageRequest.of(1, 4, Sort.by(Sort.Direction.DESC, "price")),
                    9);
            when(productService.findAllByCategory(any(UUID.class), any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCTS_BY_CATEGORY_URL, categoryId)
                            .param("page", "1")
                            .param("size", "4")
                            .param("sort", "price,desc"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.number").value(1))
                    .andExpect(jsonPath("$.size").value(4))
                    .andExpect(jsonPath("$.totalElements").value(9))
                    .andExpect(jsonPath("$.totalPages").value(3));

            ArgumentCaptor<UUID> categoryCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productService).findAllByCategory(categoryCaptor.capture(), pageableCaptor.capture());

            assertThat(categoryCaptor.getValue()).isEqualTo(categoryId);
            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(1);
            assertThat(pageable.getPageSize()).isEqualTo(4);
            assertThat(pageable.getSort().getOrderFor("price")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        void getProductsByCategory_shouldReturnEmptyPageWhenNoDataExists() throws Exception {
            UUID categoryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            Page<ProductResponseDTO> response = new PageImpl<>(List.of(), PageRequest.of(0, 12), 0);
            when(productService.findAllByCategory(any(UUID.class), any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCTS_BY_CATEGORY_URL, categoryId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content", empty()))
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));

            verify(productService).findAllByCategory(any(UUID.class), any(Pageable.class));
        }

        @Test
        void getProductsByCategory_shouldReturnBadRequestWhenCategoryIdIsInvalidUuid() throws Exception {
            mockMvc.perform(get(PRODUCTS_BY_CATEGORY_URL, "invalid-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: categoryId"))
                    .andExpect(jsonPath("$.errors.parameter").value("categoryId"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }

        @Test
        void getProductsByCategory_shouldReturnBadRequestWhenSizeIsInvalidType() throws Exception {
            UUID categoryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

            mockMvc.perform(get(PRODUCTS_BY_CATEGORY_URL, categoryId).param("size", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter: size"))
                    .andExpect(jsonPath("$.errors.parameter").value("size"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }
    }

    @Nested
    class GetProductBySlug {

        @Test
        void getProductBySlug_shouldReturnOkAndDelegateToService() throws Exception {
            when(productService.findBySlug("keyboard"))
                    .thenReturn(sampleProduct(UUID.fromString("55555555-5555-5555-5555-555555555555"), "Keyboard", "keyboard"));

            mockMvc.perform(get(PRODUCT_BY_SLUG_URL, "keyboard"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("55555555-5555-5555-5555-555555555555"))
                    .andExpect(jsonPath("$.name").value("Keyboard"))
                    .andExpect(jsonPath("$.slug").value("keyboard"))
                    .andExpect(jsonPath("$.price").value(199.99))
                    .andExpect(jsonPath("$.categoryName").value("Peripherals"));

            verify(productService).findBySlug("keyboard");
        }

        @Test
        void getProductBySlug_shouldReturnNotFoundWhenProductDoesNotExist() throws Exception {
            when(productService.findBySlug("missing-slug")).thenThrow(new ProductNotFoundException("missing-slug"));

            mockMvc.perform(get(PRODUCT_BY_SLUG_URL, "missing-slug"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Product not found for slug: missing-slug"))
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).findBySlug("missing-slug");
        }
    }

    @Nested
    class SearchProducts {

        @Test
        void searchProducts_shouldBindFullCriteriaAndUseDefaultPageable() throws Exception {
            when(productService.searchProducts(any(ProductSearchCriteria.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(sampleProduct(UUID.fromString("66666666-6666-6666-6666-666666666666"), "Monitor", "monitor")),
                            PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "createdAt")),
                            1));

            mockMvc.perform(get(SEARCH_PRODUCTS_URL)
                            .param("query", "monitor")
                            .param("categoryId", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
                            .param("minPrice", "100")
                            .param("maxPrice", "200")
                            .param("minRating", "4"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].name").value("Monitor"))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1));

            ArgumentCaptor<ProductSearchCriteria> criteriaCaptor = ArgumentCaptor.forClass(ProductSearchCriteria.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productService).searchProducts(criteriaCaptor.capture(), pageableCaptor.capture());

            ProductSearchCriteria criteria = criteriaCaptor.getValue();
            assertThat(criteria.query()).isEqualTo("monitor");
            assertThat(criteria.categoryId()).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
            assertThat(criteria.minPrice()).isEqualByComparingTo(new BigDecimal("100"));
            assertThat(criteria.maxPrice()).isEqualByComparingTo(new BigDecimal("200"));
            assertThat(criteria.minRating()).isEqualTo(4.0);

            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(0);
            assertThat(pageable.getPageSize()).isEqualTo(12);
            assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        }

        @Test
        void searchProducts_shouldBindMinimalCriteriaAndCustomPageableSort() throws Exception {
            when(productService.searchProducts(any(ProductSearchCriteria.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(sampleProduct(UUID.fromString("77777777-7777-7777-7777-777777777777"), "Mouse", "mouse")),
                            PageRequest.of(1, 3, Sort.by(Sort.Direction.ASC, "name")),
                            8));

            mockMvc.perform(get(SEARCH_PRODUCTS_URL)
                            .param("query", "mouse")
                            .param("page", "1")
                            .param("size", "3")
                            .param("sort", "name,asc"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.number").value(1))
                    .andExpect(jsonPath("$.size").value(3))
                    .andExpect(jsonPath("$.totalElements").value(8))
                    .andExpect(jsonPath("$.totalPages").value(3));

            ArgumentCaptor<ProductSearchCriteria> criteriaCaptor = ArgumentCaptor.forClass(ProductSearchCriteria.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productService).searchProducts(criteriaCaptor.capture(), pageableCaptor.capture());

            ProductSearchCriteria criteria = criteriaCaptor.getValue();
            assertThat(criteria.query()).isEqualTo("mouse");
            assertThat(criteria.categoryId()).isNull();
            assertThat(criteria.minPrice()).isNull();
            assertThat(criteria.maxPrice()).isNull();
            assertThat(criteria.minRating()).isNull();

            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(1);
            assertThat(pageable.getPageSize()).isEqualTo(3);
            assertThat(pageable.getSort().getOrderFor("name")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.ASC);
        }

        @Test
        void searchProducts_shouldSupportNoCriteriaParams() throws Exception {
            Page<ProductResponseDTO> response = new PageImpl<>(List.of(), PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "createdAt")), 0);
            when(productService.searchProducts(any(ProductSearchCriteria.class), any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(SEARCH_PRODUCTS_URL))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content", empty()))
                    .andExpect(jsonPath("$.content.length()").value(0))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12))
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));

            ArgumentCaptor<ProductSearchCriteria> criteriaCaptor = ArgumentCaptor.forClass(ProductSearchCriteria.class);
            verify(productService).searchProducts(criteriaCaptor.capture(), any(Pageable.class));

            ProductSearchCriteria criteria = criteriaCaptor.getValue();
            assertThat(criteria.query()).isNull();
            assertThat(criteria.categoryId()).isNull();
            assertThat(criteria.minPrice()).isNull();
            assertThat(criteria.maxPrice()).isNull();
            assertThat(criteria.minRating()).isNull();
        }

        @Test
        void searchProducts_shouldReturnBadRequestWhenCategoryIdIsInvalidUuid() throws Exception {
            mockMvc.perform(get(SEARCH_PRODUCTS_URL).param("categoryId", "invalid-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.categoryId").isArray())
                    .andExpect(jsonPath("$.errors.categoryId[0]").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }

        @Test
        void searchProducts_shouldReturnBadRequestWhenMinRatingIsInvalidType() throws Exception {
            mockMvc.perform(get(SEARCH_PRODUCTS_URL).param("minRating", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.minRating").isArray())
                    .andExpect(jsonPath("$.errors.minRating[0]").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }

        @Test
        void searchProducts_shouldReturnBadRequestWhenMinPriceIsInvalidType() throws Exception {
            mockMvc.perform(get(SEARCH_PRODUCTS_URL).param("minPrice", "abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.minPrice").isArray())
                    .andExpect(jsonPath("$.errors.minPrice[0]").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }
    }

    private ProductResponseDTO sampleProduct(UUID id, String name, String slug) {
        return new ProductResponseDTO(
                id,
                name,
                slug,
                "SKU-001",
                "Description",
                new BigDecimal("199.99"),
                10,
                UUID.fromString("99999999-9999-9999-9999-999999999999"),
                "Peripherals",
                4.5,
                12,
                List.of("https://cdn.example.com/p1.jpg"));
    }
}