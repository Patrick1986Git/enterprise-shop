package com.company.shop.module.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.company.shop.support.WebMvcSliceTestConfig;
import com.company.shop.module.product.dto.ProductReviewRequestDTO;
import com.company.shop.module.product.dto.ProductReviewResponseDTO;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.exception.ProductReviewAccessDeniedException;
import com.company.shop.module.product.exception.ProductReviewAlreadyExistsException;
import com.company.shop.module.product.exception.ProductReviewNotFoundException;
import com.company.shop.module.product.service.ProductReviewService;
import com.company.shop.security.UserDetailsServiceImpl;
import com.company.shop.security.jwt.JwtAuthenticationFilter;
import com.company.shop.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = ProductReviewController.class)
@Import(WebMvcSliceTestConfig.class)
class ProductReviewControllerWebMvcTest {

    private static final String REVIEWS_URL = "/api/v1/reviews";
    private static final String PRODUCT_REVIEWS_URL = "/api/v1/products/{productId}/reviews";
    private static final String REVIEW_BY_ID_URL = "/api/v1/reviews/{reviewId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductReviewService productReviewService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        when(jwtTokenProvider.validate(anyString())).thenReturn(false);
    }

    @Nested
    class CreateReview {

        @Test
        void createReview_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            ProductReviewRequestDTO request = new ProductReviewRequestDTO(
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    5,
                    "Świetny produkt");

            mockMvc.perform(post(REVIEWS_URL)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void createReview_shouldReturnForbiddenForAuthenticatedWhenCsrfMissing() throws Exception {
            ProductReviewRequestDTO request = new ProductReviewRequestDTO(
                    UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    5,
                    "Świetny produkt");

            mockMvc.perform(post(REVIEWS_URL)
                            .with(user("user").roles("USER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void createReview_shouldReturnCreatedForAuthenticatedWithCsrfAndDelegateExactPayload() throws Exception {
            UUID productId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            ProductReviewRequestDTO request = new ProductReviewRequestDTO(productId, 4, "Dobry produkt");
            ProductReviewResponseDTO response = sampleReview(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    "Jan Kowalski",
                    4,
                    "Dobry produkt");

            when(productReviewService.addReview(any(ProductReviewRequestDTO.class))).thenReturn(response);

            mockMvc.perform(post(REVIEWS_URL)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value("11111111-1111-1111-1111-111111111111"))
                    .andExpect(jsonPath("$.authorName").value("Jan Kowalski"))
                    .andExpect(jsonPath("$.rating").value(4))
                    .andExpect(jsonPath("$.comment").value("Dobry produkt"))
                    .andExpect(jsonPath("$.createdAt").exists());

            ArgumentCaptor<ProductReviewRequestDTO> captor = ArgumentCaptor.forClass(ProductReviewRequestDTO.class);
            verify(productReviewService).addReview(captor.capture());

            ProductReviewRequestDTO captured = captor.getValue();
            assertThat(captured.productId()).isEqualTo(productId);
            assertThat(captured.rating()).isEqualTo(4);
            assertThat(captured.comment()).isEqualTo("Dobry produkt");
        }

        @Test
        void createReview_shouldReturnBadRequestWhenValidationFailsAndNotCallService() throws Exception {
            String invalidBody = """
                    {
                      "productId": null,
                      "rating": 0,
                      "comment": "ok"
                    }
                    """;

            mockMvc.perform(post(REVIEWS_URL)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.productId").isArray())
                    .andExpect(jsonPath("$.errors.productId", not(empty())))
                    .andExpect(jsonPath("$.errors.rating").isArray())
                    .andExpect(jsonPath("$.errors.rating", not(empty())))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void createReview_shouldReturnBadRequestWhenCommentExceedsMaxLength() throws Exception {
            String tooLongComment = "a".repeat(1001);
            String invalidBody = """
                    {
                      "productId": "%s",
                      "rating": 5,
                      "comment": "%s"
                    }
                    """.formatted(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), tooLongComment);

            mockMvc.perform(post(REVIEWS_URL)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.comment").isArray())
                    .andExpect(jsonPath("$.errors.comment", not(empty())))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void createReview_shouldReturnConflictWhenReviewAlreadyExists() throws Exception {
            UUID productId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            ProductReviewRequestDTO request = new ProductReviewRequestDTO(productId, 5, "Świetny produkt");
            when(productReviewService.addReview(any(ProductReviewRequestDTO.class)))
                    .thenThrow(new ProductReviewAlreadyExistsException(productId));

            mockMvc.perform(post(REVIEWS_URL)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_REVIEW_ALREADY_EXISTS"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productReviewService).addReview(any(ProductReviewRequestDTO.class));
        }

        @Test
        void createReview_shouldReturnNotFoundWhenProductDoesNotExist() throws Exception {
            UUID missingProductId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
            ProductReviewRequestDTO request = new ProductReviewRequestDTO(missingProductId, 5, "Komentarz");
            when(productReviewService.addReview(any(ProductReviewRequestDTO.class)))
                    .thenThrow(new ProductNotFoundException(missingProductId));

            mockMvc.perform(post(REVIEWS_URL)
                            .with(user("user").roles("USER"))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productReviewService).addReview(any(ProductReviewRequestDTO.class));
        }
    }

    @Nested
    class GetProductReviews {

        @Test
        void getProductReviews_shouldAllowAnonymousAndReturnPagedResponse() throws Exception {
            UUID productId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
            Page<ProductReviewResponseDTO> response = new PageImpl<>(
                    List.of(sampleReview(
                            UUID.fromString("22222222-2222-2222-2222-222222222222"),
                            "Anna Nowak",
                            5,
                            "Polecam")),
                    PageRequest.of(0, 20),
                    1);
            when(productReviewService.getProductReviews(any(UUID.class), any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCT_REVIEWS_URL, productId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value("22222222-2222-2222-2222-222222222222"))
                    .andExpect(jsonPath("$.content[0].authorName").value("Anna Nowak"))
                    .andExpect(jsonPath("$.content[0].rating").value(5))
                    .andExpect(jsonPath("$.content[0].comment").value("Polecam"))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalElements").value(1));

            ArgumentCaptor<UUID> productCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productReviewService).getProductReviews(productCaptor.capture(), pageableCaptor.capture());

            assertThat(productCaptor.getValue()).isEqualTo(productId);
            assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        }

        @Test
        void getProductReviews_shouldMapCustomPaginationAndSortToPageable() throws Exception {
            UUID productId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
            Page<ProductReviewResponseDTO> response = new PageImpl<>(List.of(), PageRequest.of(2, 5), 0);
            when(productReviewService.getProductReviews(any(UUID.class), any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCT_REVIEWS_URL, productId)
                            .param("page", "2")
                            .param("size", "5")
                            .param("sort", "createdAt,desc"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.number").value(2))
                    .andExpect(jsonPath("$.size").value(5));

            ArgumentCaptor<UUID> productCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productReviewService).getProductReviews(productCaptor.capture(), pageableCaptor.capture());

            assertThat(productCaptor.getValue()).isEqualTo(productId);
            Pageable pageable = pageableCaptor.getValue();
            assertThat(pageable.getPageNumber()).isEqualTo(2);
            assertThat(pageable.getPageSize()).isEqualTo(5);
            assertThat(pageable.getSort().getOrderFor("createdAt")).isNotNull();
            assertThat(pageable.getSort().getOrderFor("createdAt").getDirection().name()).isEqualTo("DESC");
        }

        @Test
        void getProductReviews_shouldReturnEmptyPageWhenNoReviewsExist() throws Exception {
            UUID productId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
            Page<ProductReviewResponseDTO> response = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
            when(productReviewService.getProductReviews(any(UUID.class), any(Pageable.class))).thenReturn(response);

            mockMvc.perform(get(PRODUCT_REVIEWS_URL, productId))
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

            ArgumentCaptor<UUID> productCaptor = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(productReviewService).getProductReviews(productCaptor.capture(), pageableCaptor.capture());

            assertThat(productCaptor.getValue()).isEqualTo(productId);
            assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
        }

        @Test
        void getProductReviews_shouldReturnBadRequestWhenPathVariableIsNotUuid() throws Exception {
            mockMvc.perform(get(PRODUCT_REVIEWS_URL, "not-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors.parameter").value("productId"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void getProductReviews_shouldReturnNotFoundWhenProductMissing() throws Exception {
            UUID missingProductId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
            when(productReviewService.getProductReviews(eq(missingProductId), any(Pageable.class)))
                    .thenThrow(new ProductNotFoundException(missingProductId));

            mockMvc.perform(get(PRODUCT_REVIEWS_URL, missingProductId))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productReviewService).getProductReviews(eq(missingProductId), any(Pageable.class));
        }
    }

    @Nested
    class DeleteReview {

        @Test
        void deleteReview_shouldReturnForbiddenForAnonymousEvenWithCsrf() throws Exception {
            UUID reviewId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

            mockMvc.perform(delete(REVIEW_BY_ID_URL, reviewId)
                            .with(csrf()))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void deleteReview_shouldReturnForbiddenForAuthenticatedWhenCsrfMissing() throws Exception {
            UUID reviewId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

            mockMvc.perform(delete(REVIEW_BY_ID_URL, reviewId)
                            .with(user("user").roles("USER")))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void deleteReview_shouldReturnNoContentForAuthenticatedWithCsrfAndDelegateToService() throws Exception {
            UUID reviewId = UUID.fromString("33333333-3333-3333-3333-333333333333");

            mockMvc.perform(delete(REVIEW_BY_ID_URL, reviewId)
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isNoContent());

            verify(productReviewService).deleteReview(reviewId);
        }

        @Test
        void deleteReview_shouldReturnBadRequestWhenReviewIdIsNotUuid() throws Exception {
            mockMvc.perform(delete(REVIEW_BY_ID_URL, "invalid")
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.errors.parameter").value("reviewId"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verifyNoInteractions(productReviewService);
        }

        @Test
        void deleteReview_shouldReturnNotFoundWhenReviewMissing() throws Exception {
            UUID missingReviewId = UUID.fromString("44444444-4444-4444-4444-444444444444");
            doThrow(new ProductReviewNotFoundException(missingReviewId))
                    .when(productReviewService).deleteReview(missingReviewId);

            mockMvc.perform(delete(REVIEW_BY_ID_URL, missingReviewId)
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_REVIEW_NOT_FOUND"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productReviewService).deleteReview(missingReviewId);
        }

        @Test
        void deleteReview_shouldReturnForbiddenWhenUserHasNoAccessToReview() throws Exception {
            UUID reviewId = UUID.fromString("55555555-5555-5555-5555-555555555555");
            doThrow(new ProductReviewAccessDeniedException())
                    .when(productReviewService).deleteReview(reviewId);

            mockMvc.perform(delete(REVIEW_BY_ID_URL, reviewId)
                            .with(user("user").roles("USER"))
                            .with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_REVIEW_ACCESS_DENIED"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(productReviewService).deleteReview(reviewId);
        }
    }

    private ProductReviewResponseDTO sampleReview(UUID id, String authorName, int rating, String comment) {
        return new ProductReviewResponseDTO(id, authorName, rating, comment, LocalDateTime.of(2026, 1, 1, 10, 0));
    }
}