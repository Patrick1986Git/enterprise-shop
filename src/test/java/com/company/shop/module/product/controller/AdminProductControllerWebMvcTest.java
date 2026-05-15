package com.company.shop.module.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.common.exception.GlobalExceptionHandler;
import com.company.shop.config.SecurityConfig;
import com.company.shop.module.product.dto.ProductCreateDTO;
import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.exception.ProductCategoryNotFoundException;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.exception.ProductSkuAlreadyExistsException;
import com.company.shop.module.product.exception.ProductSlugAlreadyExistsException;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.company.shop.support.TestMeterRegistryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AdminProductController.class)
@Import({ SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, TestMeterRegistryConfig.class })
class AdminProductControllerWebMvcTest {

    private static final String ADMIN_PRODUCTS_URL = "/api/v1/admin/products";
    private static final String ADMIN_PRODUCT_BY_ID_URL = "/api/v1/admin/products/{id}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    class GetProductById {

        @Test
        void getProductById_shouldReturnForbiddenForAnonymous() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(get(ADMIN_PRODUCT_BY_ID_URL, id))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void getProductById_shouldReturnForbiddenForUserRole() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(get(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("user").roles("USER")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void getProductById_shouldReturnOkForAdminAndDelegateToServiceWithExactId() throws Exception {
            UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
            ProductResponseDTO response = sampleProduct(id, "Gaming Laptop", "gaming-laptop", "SKU-100");
            when(productService.findById(eq(id))).thenReturn(response);

            mockMvc.perform(get(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.name").value("Gaming Laptop"))
                    .andExpect(jsonPath("$.slug").value("gaming-laptop"))
                    .andExpect(jsonPath("$.sku").value("SKU-100"))
                    .andExpect(jsonPath("$.price").value(199.99))
                    .andExpect(jsonPath("$.stock").value(12))
                    .andExpect(jsonPath("$.categoryName").value("Peripherals"))
                    .andExpect(jsonPath("$.averageRating").value(4.8))
                    .andExpect(jsonPath("$.reviewCount").value(5))
                    .andExpect(jsonPath("$.imageUrls").isArray())
                    .andExpect(jsonPath("$.imageUrls.length()").value(2));

            verify(productService).findById(eq(id));
        }

        @Test
        void getProductById_shouldReturnNotFoundApiErrorWhenProductMissing() throws Exception {
            UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
            when(productService.findById(eq(id))).thenThrow(new ProductNotFoundException(id));

            mockMvc.perform(get(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).findById(eq(id));
        }

        @Test
        void getProductById_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            mockMvc.perform(get(ADMIN_PRODUCT_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors.parameter").value("id"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }
    }

    @Nested
    class CreateProduct {

        @Test
        void createProduct_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            ProductCreateDTO request = sampleCreateDto();

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void createProduct_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            ProductCreateDTO request = sampleCreateDto();

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void createProduct_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            ProductCreateDTO request = sampleCreateDto();

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void createProduct_shouldReturnCreatedForAdminAndPassExactDtoToService() throws Exception {
            UUID categoryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            ProductCreateDTO request = new ProductCreateDTO(
                    "Gaming Laptop",
                    "SKU-200",
                    "Opis laptopa",
                    new BigDecimal("3999.00"),
                    10,
                    categoryId,
                    List.of("https://cdn.example.com/products/laptop-1.jpg", "https://cdn.example.com/products/laptop-2.jpg"));
            ProductResponseDTO response = sampleProduct(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    "Gaming Laptop",
                    "gaming-laptop",
                    "SKU-200",
                    new BigDecimal("3999.00"),
                    10);
            when(productService.create(any(ProductCreateDTO.class))).thenReturn(response);

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("33333333-3333-3333-3333-333333333333"))
                    .andExpect(jsonPath("$.name").value("Gaming Laptop"))
                    .andExpect(jsonPath("$.slug").value("gaming-laptop"))
                    .andExpect(jsonPath("$.sku").value("SKU-200"))
                    .andExpect(jsonPath("$.price").value(3999.00))
                    .andExpect(jsonPath("$.stock").value(10))
                    .andExpect(jsonPath("$.categoryName").value("Peripherals"));

            ArgumentCaptor<ProductCreateDTO> dtoCaptor = ArgumentCaptor.forClass(ProductCreateDTO.class);
            verify(productService).create(dtoCaptor.capture());
            verifyNoMoreInteractions(productService);

            ProductCreateDTO captured = dtoCaptor.getValue();
            assertThat(captured.getName()).isEqualTo("Gaming Laptop");
            assertThat(captured.getSku()).isEqualTo("SKU-200");
            assertThat(captured.getDescription()).isEqualTo("Opis laptopa");
            assertThat(captured.getPrice()).isEqualByComparingTo("3999.00");
            assertThat(captured.getStock()).isEqualTo(10);
            assertThat(captured.getCategoryId()).isEqualTo(categoryId);
            assertThat(captured.getImageUrls()).containsExactly(
                    "https://cdn.example.com/products/laptop-1.jpg",
                    "https://cdn.example.com/products/laptop-2.jpg");
        }

        @Test
        void createProduct_shouldReturnBadRequestWhenNameIsBlank() throws Exception {
            String invalidBody = """
                    {
                      "name": "",
                      "sku": "SKU-200",
                      "description": "Opis",
                      "price": 199.99,
                      "stock": 1,
                      "categoryId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                      "imageUrls": ["https://cdn.example.com/products/laptop-1.jpg"]
                    }
                    """;

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
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

            verifyNoInteractions(productService);
        }

        @Test
        void createProduct_shouldReturnBadRequestWhenPriceIsInvalid() throws Exception {
            String invalidBody = """
                    {
                      "name": "Gaming Laptop",
                      "sku": "SKU-200",
                      "description": "Opis",
                      "price": 0,
                      "stock": 1,
                      "categoryId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                      "imageUrls": []
                    }
                    """;

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.price").isArray())
                    .andExpect(jsonPath("$.errors.price", not(empty())));

            verifyNoInteractions(productService);
        }

        @Test
        void createProduct_shouldReturnBadRequestWhenDescriptionExceedsMaxLength() throws Exception {
            String invalidBody = """
                    {
                      "name": "Gaming Laptop",
                      "sku": "SKU-200",
                      "description": "%s",
                      "price": 199.99,
                      "stock": 1,
                      "categoryId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                      "imageUrls": []
                    }
                    """.formatted("a".repeat(5001));

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.description").isArray())
                    .andExpect(jsonPath("$.errors.description", not(empty())))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productService);
        }

        @Test
        void createProduct_shouldReturnConflictWhenSkuAlreadyExists() throws Exception {
            ProductCreateDTO request = sampleCreateDto();
            when(productService.create(any(ProductCreateDTO.class)))
                    .thenThrow(new ProductSkuAlreadyExistsException("SKU-200"));

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_SKU_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).create(any(ProductCreateDTO.class));
        }

        @Test
        void createProduct_shouldReturnConflictWhenSlugAlreadyExists() throws Exception {
            ProductCreateDTO request = sampleCreateDto();
            when(productService.create(any(ProductCreateDTO.class)))
                    .thenThrow(new ProductSlugAlreadyExistsException("gaming-laptop"));

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_SLUG_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).create(any(ProductCreateDTO.class));
        }

        @Test
        void createProduct_shouldReturnNotFoundWhenCategoryMissing() throws Exception {
            UUID categoryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            ProductCreateDTO request = sampleCreateDto();
            when(productService.create(any(ProductCreateDTO.class)))
                    .thenThrow(new ProductCategoryNotFoundException(categoryId));

            mockMvc.perform(post(ADMIN_PRODUCTS_URL)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_CATEGORY_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).create(any(ProductCreateDTO.class));
        }
    }

    @Nested
    class UpdateProduct {

        @Test
        void updateProduct_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();
            ProductCreateDTO request = sampleCreateDto();

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void updateProduct_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();
            ProductCreateDTO request = sampleCreateDto();

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void updateProduct_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            UUID id = UUID.randomUUID();
            ProductCreateDTO request = sampleCreateDto();

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void updateProduct_shouldReturnOkForAdminAndPassExactIdAndDtoToService() throws Exception {
            UUID id = UUID.fromString("44444444-4444-4444-4444-444444444444");
            UUID categoryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            ProductCreateDTO request = new ProductCreateDTO(
                    "Gaming Laptop Pro",
                    "SKU-300",
                    "Opis po zmianie",
                    new BigDecimal("4999.00"),
                    20,
                    categoryId,
                    List.of("https://cdn.example.com/products/laptop-pro-1.jpg"));
            ProductResponseDTO response = sampleProduct(
                    id,
                    "Gaming Laptop Pro",
                    "gaming-laptop-pro",
                    "SKU-300",
                    new BigDecimal("4999.00"),
                    20);
            when(productService.update(eq(id), any(ProductCreateDTO.class))).thenReturn(response);

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("44444444-4444-4444-4444-444444444444"))
                    .andExpect(jsonPath("$.name").value("Gaming Laptop Pro"))
                    .andExpect(jsonPath("$.slug").value("gaming-laptop-pro"))
                    .andExpect(jsonPath("$.sku").value("SKU-300"))
                    .andExpect(jsonPath("$.price").value(4999.00))
                    .andExpect(jsonPath("$.stock").value(20));

            ArgumentCaptor<ProductCreateDTO> dtoCaptor = ArgumentCaptor.forClass(ProductCreateDTO.class);
            verify(productService).update(eq(id), dtoCaptor.capture());
            verifyNoMoreInteractions(productService);

            ProductCreateDTO captured = dtoCaptor.getValue();
            assertThat(captured.getName()).isEqualTo("Gaming Laptop Pro");
            assertThat(captured.getSku()).isEqualTo("SKU-300");
            assertThat(captured.getDescription()).isEqualTo("Opis po zmianie");
            assertThat(captured.getPrice()).isEqualByComparingTo("4999.00");
            assertThat(captured.getStock()).isEqualTo(20);
            assertThat(captured.getCategoryId()).isEqualTo(categoryId);
            assertThat(captured.getImageUrls()).containsExactly("https://cdn.example.com/products/laptop-pro-1.jpg");
        }

        @Test
        void updateProduct_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            ProductCreateDTO request = sampleCreateDto();

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors.parameter").value("id"));

            verifyNoInteractions(productService);
        }

        @Test
        void updateProduct_shouldReturnBadRequestWhenBodyValidationFails() throws Exception {
            UUID id = UUID.randomUUID();
            String invalidBody = """
                    {
                      "name": "Gaming Laptop",
                      "sku": "SKU-300",
                      "description": "Opis",
                      "price": 199.99,
                      "stock": -1,
                      "categoryId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                      "imageUrls": []
                    }
                    """;

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.stock").isArray())
                    .andExpect(jsonPath("$.errors.stock", not(empty())));

            verifyNoInteractions(productService);
        }

        @Test
        void updateProduct_shouldReturnNotFoundWhenProductMissing() throws Exception {
            UUID id = UUID.fromString("55555555-5555-5555-5555-555555555555");
            ProductCreateDTO request = sampleCreateDto();
            when(productService.update(eq(id), any(ProductCreateDTO.class)))
                    .thenThrow(new ProductNotFoundException(id));

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).update(eq(id), any(ProductCreateDTO.class));
        }

        @Test
        void updateProduct_shouldReturnConflictWhenSkuAlreadyExists() throws Exception {
            UUID id = UUID.randomUUID();
            ProductCreateDTO request = sampleCreateDto();
            when(productService.update(eq(id), any(ProductCreateDTO.class)))
                    .thenThrow(new ProductSkuAlreadyExistsException("SKU-300"));

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_SKU_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).update(eq(id), any(ProductCreateDTO.class));
        }

        @Test
        void updateProduct_shouldReturnConflictWhenSlugAlreadyExists() throws Exception {
            UUID id = UUID.randomUUID();
            ProductCreateDTO request = sampleCreateDto();
            when(productService.update(eq(id), any(ProductCreateDTO.class)))
                    .thenThrow(new ProductSlugAlreadyExistsException("gaming-laptop-pro"));

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_SLUG_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).update(eq(id), any(ProductCreateDTO.class));
        }

        @Test
        void updateProduct_shouldReturnNotFoundWhenCategoryMissing() throws Exception {
            UUID id = UUID.randomUUID();
            UUID categoryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            ProductCreateDTO request = sampleCreateDto();
            when(productService.update(eq(id), any(ProductCreateDTO.class)))
                    .thenThrow(new ProductCategoryNotFoundException(categoryId));

            mockMvc.perform(put(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_CATEGORY_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).update(eq(id), any(ProductCreateDTO.class));
        }
    }

    @Nested
    class DeleteProduct {

        @Test
        void deleteProduct_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void deleteProduct_shouldReturnForbiddenForUserRoleEvenWithCsrf() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void deleteProduct_shouldReturnForbiddenForAdminWhenCsrfMissing() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productService);
        }

        @Test
        void deleteProduct_shouldReturnNoContentForAdminWhenProductExistsAndDelegateExactId() throws Exception {
            UUID id = UUID.fromString("77777777-7777-7777-7777-777777777777");

            mockMvc.perform(delete(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isNoContent());

            verify(productService).delete(eq(id));
        }

        @Test
        void deleteProduct_shouldReturnNotFoundWhenProductMissing() throws Exception {
            UUID id = UUID.fromString("66666666-6666-6666-6666-666666666666");
            doThrow(new ProductNotFoundException(id)).when(productService).delete(eq(id));

            mockMvc.perform(delete(ADMIN_PRODUCT_BY_ID_URL, id)
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors").value(nullValue()))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productService).delete(eq(id));
        }

        @Test
        void deleteProduct_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            mockMvc.perform(delete(ADMIN_PRODUCT_BY_ID_URL, "not-a-uuid")
                            .with(user("admin").roles("ADMIN"))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors.parameter").value("id"));

            verifyNoInteractions(productService);
        }
    }

    private ProductCreateDTO sampleCreateDto() {
        return new ProductCreateDTO(
                "Gaming Laptop",
                "SKU-200",
                "Opis produktu",
                new BigDecimal("199.99"),
                12,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                List.of("https://cdn.example.com/products/laptop-1.jpg", "https://cdn.example.com/products/laptop-2.jpg"));
    }

    private ProductResponseDTO sampleProduct(UUID id, String name, String slug, String sku) {
        return sampleProduct(id, name, slug, sku, new BigDecimal("199.99"), 12);
    }

    private ProductResponseDTO sampleProduct(UUID id, String name, String slug, String sku, BigDecimal price, int stock) {
        return new ProductResponseDTO(
                id,
                name,
                slug,
                sku,
                "Opis produktu",
                price,
                stock,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "Peripherals",
                4.8,
                5,
                List.of("https://cdn.example.com/products/laptop-1.jpg", "https://cdn.example.com/products/laptop-2.jpg"));
    }
}
