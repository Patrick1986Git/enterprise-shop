package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.dto.ProductReviewRequestDTO;
import com.company.shop.module.product.dto.ProductReviewResponseDTO;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.entity.ProductReview;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.exception.ProductReviewAccessDeniedException;
import com.company.shop.module.product.exception.ProductReviewAlreadyExistsException;
import com.company.shop.module.product.exception.ProductReviewCountInvalidException;
import com.company.shop.module.product.exception.ProductReviewNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.product.repository.ProductReviewRepository;
import com.company.shop.module.product.repository.RatingStats;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class ProductReviewServiceImplTest {

    @Mock
    private ProductReviewRepository reviewRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserService userService;

    private ProductReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductReviewServiceImpl(reviewRepository, productRepository, userService);
    }

    @Test
    void addReview_shouldPersistUpdateStatisticsAndMapCompleteResponse() {
        UUID productId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 7, 12, 30);
        User user = user(userId, "Alex", "Morgan");
        Product product = product(productId);
        ProductReviewRequestDTO request = new ProductReviewRequestDTO(productId, 5, "Excellent");

        when(userService.getCurrentUserEntity()).thenReturn(user);
        when(reviewRepository.existsByProductIdAndUserId(productId, userId)).thenReturn(false);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.saveAndFlush(any(ProductReview.class))).thenAnswer(invocation -> {
            ProductReview review = invocation.getArgument(0);
            setEntityField(review, "id", reviewId);
            setEntityField(review, "createdAt", createdAt);
            return review;
        });
        when(reviewRepository.getRatingStatsByProductId(productId)).thenReturn(new RatingStats(4.25, 8));

        ProductReviewResponseDTO response = service.addReview(request);

        ArgumentCaptor<ProductReview> reviewCaptor = ArgumentCaptor.forClass(ProductReview.class);
        verify(reviewRepository).saveAndFlush(reviewCaptor.capture());
        ProductReview persistedReview = reviewCaptor.getValue();
        assertThat(persistedReview.getProduct()).isSameAs(product);
        assertThat(persistedReview.getUser()).isSameAs(user);
        assertThat(persistedReview.getRating()).isEqualTo(5);
        assertThat(persistedReview.getComment()).isEqualTo("Excellent");
        assertThat(product.getAverageRating()).isEqualTo(4.25);
        assertThat(product.getReviewCount()).isEqualTo(8);
        verify(productRepository).save(product);
        assertThat(response.id()).isEqualTo(reviewId);
        assertThat(response.authorName()).isEqualTo("Alex Morgan");
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Excellent");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void addReview_shouldThrowWhenReviewAlreadyExistsWithoutDownstreamWork() {
        UUID productId = UUID.randomUUID();
        User user = user(UUID.randomUUID(), "Alex", "Morgan");
        when(userService.getCurrentUserEntity()).thenReturn(user);
        when(reviewRepository.existsByProductIdAndUserId(productId, user.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.addReview(new ProductReviewRequestDTO(productId, 5, "Great")))
                .isInstanceOf(ProductReviewAlreadyExistsException.class)
                .hasMessageContaining(productId.toString());

        verify(productRepository, never()).findById(any(UUID.class));
        verify(reviewRepository, never()).saveAndFlush(any(ProductReview.class));
        verify(reviewRepository, never()).getRatingStatsByProductId(any(UUID.class));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void addReview_shouldThrowWhenProductMissingWithoutPersistenceOrRatingUpdate() {
        UUID productId = UUID.randomUUID();
        User user = user(UUID.randomUUID(), "Alex", "Morgan");
        when(userService.getCurrentUserEntity()).thenReturn(user);
        when(reviewRepository.existsByProductIdAndUserId(productId, user.getId())).thenReturn(false);
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addReview(new ProductReviewRequestDTO(productId, 4, "Good")))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(productId.toString());

        verify(reviewRepository, never()).saveAndFlush(any(ProductReview.class));
        verify(reviewRepository, never()).getRatingStatsByProductId(any(UUID.class));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void addReview_shouldTranslateNestedUserProductConstraintViolation() {
        UUID productId = UUID.randomUUID();
        DataIntegrityViolationException failure = dataIntegrityViolation("uk_user_product_review");
        prepareAddForPersistenceFailure(productId, failure);

        assertThatThrownBy(() -> service.addReview(new ProductReviewRequestDTO(productId, 4, "Good")))
                .isInstanceOf(ProductReviewAlreadyExistsException.class)
                .hasMessageContaining(productId.toString());

        verifyNoRatingSideEffects();
    }

    @Test
    void addReview_shouldMatchUserProductConstraintCaseInsensitively() {
        UUID productId = UUID.randomUUID();
        prepareAddForPersistenceFailure(productId, dataIntegrityViolation("UK_USER_PRODUCT_REVIEW"));

        assertThatThrownBy(() -> service.addReview(new ProductReviewRequestDTO(productId, 4, "Good")))
                .isInstanceOf(ProductReviewAlreadyExistsException.class);

        verifyNoRatingSideEffects();
    }

    @Test
    void addReview_shouldRethrowSameFailureForUnknownConstraint() {
        assertUnrecognizedIntegrityFailureIsRethrown(dataIntegrityViolation("unknown_constraint"));
    }

    @Test
    void addReview_shouldRethrowSameFailureWhenConstraintNameMissing() {
        assertUnrecognizedIntegrityFailureIsRethrown(dataIntegrityViolation(null));
    }

    @Test
    void addReview_shouldRethrowSameUnrelatedIntegrityFailure() {
        assertUnrecognizedIntegrityFailureIsRethrown(new DataIntegrityViolationException("unrelated"));
    }

    @Test
    void addReview_shouldResetRatingsWhenStatisticsAreNull() {
        Product product = addReviewWithStats(null);

        assertThat(product.getAverageRating()).isZero();
        assertThat(product.getReviewCount()).isZero();
        verify(productRepository).save(product);
    }

    @Test
    void addReview_shouldUseZeroAverageWhenStatisticsAverageIsNull() {
        Product product = addReviewWithStats(new RatingStats(null, 3));

        assertThat(product.getAverageRating()).isZero();
        assertThat(product.getReviewCount()).isEqualTo(3);
        verify(productRepository).save(product);
    }

    @Test
    void addReview_shouldThrowOnReviewCountOverflowWithoutSavingProduct() {
        UUID productId = UUID.randomUUID();
        Product product = prepareSuccessfulReviewPersistence(productId);
        when(reviewRepository.getRatingStatsByProductId(productId))
                .thenReturn(new RatingStats(4.0, (long) Integer.MAX_VALUE + 1));

        assertThatThrownBy(() -> service.addReview(new ProductReviewRequestDTO(productId, 4, "Good")))
                .isInstanceOf(ProductReviewCountInvalidException.class)
                .hasMessageContaining(productId.toString());

        verify(productRepository, never()).save(product);
    }

    @Test
    void getProductReviews_shouldDelegatePageableAndMapEveryReview() {
        UUID productId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(2, 5);
        Product product = product(productId);
        ProductReview first = review(UUID.randomUUID(), product, user(UUID.randomUUID(), "Alex", "Morgan"), 5,
                "Excellent", LocalDateTime.of(2026, 8, 1, 10, 0));
        ProductReview second = review(UUID.randomUUID(), product, user(UUID.randomUUID(), "Jamie", "Lee"), 3,
                "Fine", LocalDateTime.of(2026, 8, 2, 11, 0));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.findByProductId(productId, pageable))
                .thenReturn(new PageImpl<>(List.of(first, second), pageable, 2));

        Page<ProductReviewResponseDTO> response = service.getProductReviews(productId, pageable);

        verify(reviewRepository).findByProductId(productId, pageable);
        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0))
                .extracting(ProductReviewResponseDTO::id, ProductReviewResponseDTO::authorName,
                        ProductReviewResponseDTO::rating, ProductReviewResponseDTO::comment,
                        ProductReviewResponseDTO::createdAt)
                .containsExactly(first.getId(), "Alex Morgan", 5, "Excellent", first.getCreatedAt());
        assertThat(response.getContent().get(1))
                .extracting(ProductReviewResponseDTO::id, ProductReviewResponseDTO::authorName,
                        ProductReviewResponseDTO::rating, ProductReviewResponseDTO::comment,
                        ProductReviewResponseDTO::createdAt)
                .containsExactly(second.getId(), "Jamie Lee", 3, "Fine", second.getCreatedAt());
    }

    @Test
    void getProductReviews_shouldThrowWhenProductMissingWithoutReviewQuery() {
        UUID productId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 10);
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductReviews(productId, pageable))
                .isInstanceOf(ProductNotFoundException.class);

        verify(reviewRepository, never()).findByProductId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void deleteReview_shouldThrowWhenReviewMissingWithoutDownstreamWork() {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteReview(reviewId))
                .isInstanceOf(ProductReviewNotFoundException.class)
                .hasMessageContaining(reviewId.toString());

        verifyNoInteractions(userService);
        verify(reviewRepository, never()).getRatingStatsByProductId(any(UUID.class));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void deleteReview_shouldAllowOwnerAndRecalculateRatings() {
        UUID userId = UUID.randomUUID();
        User owner = user(userId, "Alex", "Morgan");
        ProductReview review = prepareDelete(owner, owner, false, new RatingStats(3.5, 2));

        service.deleteReview(review.getId());

        assertThat(review.isDeleted()).isTrue();
        assertThat(review.getProduct().getAverageRating()).isEqualTo(3.5);
        assertThat(review.getProduct().getReviewCount()).isEqualTo(2);
        verify(productRepository).save(review.getProduct());
    }

    @Test
    void deleteReview_shouldAllowNonOwnerAdminAndRecalculateRatings() {
        User owner = user(UUID.randomUUID(), "Alex", "Morgan");
        User admin = user(UUID.randomUUID(), "Admin", "User");
        ProductReview review = prepareDelete(owner, admin, true, new RatingStats(4.0, 1));

        service.deleteReview(review.getId());

        assertThat(review.isDeleted()).isTrue();
        verify(userService).isAdmin(admin);
        verify(reviewRepository).getRatingStatsByProductId(review.getProduct().getId());
        verify(productRepository).save(review.getProduct());
    }

    @Test
    void deleteReview_shouldDenyNonOwnerNonAdminWithoutSideEffects() {
        User owner = user(UUID.randomUUID(), "Alex", "Morgan");
        User currentUser = user(UUID.randomUUID(), "Jamie", "Lee");
        ProductReview review = prepareDelete(owner, currentUser, false, null);

        assertThatThrownBy(() -> service.deleteReview(review.getId()))
                .isInstanceOf(ProductReviewAccessDeniedException.class);

        assertThat(review.isDeleted()).isFalse();
        verify(reviewRepository, never()).getRatingStatsByProductId(any(UUID.class));
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void deleteReview_shouldResetRatingsWhenPostDeleteStatisticsAreEmpty() {
        User owner = user(UUID.randomUUID(), "Alex", "Morgan");
        ProductReview review = prepareDelete(owner, owner, false, new RatingStats(null, 0));
        review.getProduct().updateRatings(4.5, 1);

        service.deleteReview(review.getId());

        assertThat(review.getProduct().getAverageRating()).isZero();
        assertThat(review.getProduct().getReviewCount()).isZero();
        verify(productRepository).save(review.getProduct());
    }

    private Product addReviewWithStats(RatingStats stats) {
        UUID productId = UUID.randomUUID();
        Product product = prepareSuccessfulReviewPersistence(productId);
        when(reviewRepository.getRatingStatsByProductId(productId)).thenReturn(stats);

        service.addReview(new ProductReviewRequestDTO(productId, 4, "Good"));
        return product;
    }

    private Product prepareSuccessfulReviewPersistence(UUID productId) {
        User user = user(UUID.randomUUID(), "Alex", "Morgan");
        Product product = product(productId);
        when(userService.getCurrentUserEntity()).thenReturn(user);
        when(reviewRepository.existsByProductIdAndUserId(productId, user.getId())).thenReturn(false);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(reviewRepository.saveAndFlush(any(ProductReview.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return product;
    }

    private void prepareAddForPersistenceFailure(UUID productId, DataIntegrityViolationException failure) {
        User user = user(UUID.randomUUID(), "Alex", "Morgan");
        when(userService.getCurrentUserEntity()).thenReturn(user);
        when(reviewRepository.existsByProductIdAndUserId(productId, user.getId())).thenReturn(false);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product(productId)));
        when(reviewRepository.saveAndFlush(any(ProductReview.class))).thenThrow(failure);
    }

    private void assertUnrecognizedIntegrityFailureIsRethrown(DataIntegrityViolationException failure) {
        UUID productId = UUID.randomUUID();
        prepareAddForPersistenceFailure(productId, failure);

        assertThatThrownBy(() -> service.addReview(new ProductReviewRequestDTO(productId, 4, "Good")))
                .isSameAs(failure);

        verifyNoRatingSideEffects();
    }

    private void verifyNoRatingSideEffects() {
        verify(reviewRepository, never()).getRatingStatsByProductId(any(UUID.class));
        verify(productRepository, never()).save(any(Product.class));
    }

    private ProductReview prepareDelete(User owner, User currentUser, boolean admin, RatingStats stats) {
        Product product = product(UUID.randomUUID());
        ProductReview review = review(UUID.randomUUID(), product, owner, 5, "Great", LocalDateTime.now());
        when(reviewRepository.findById(review.getId())).thenReturn(Optional.of(review));
        when(userService.getCurrentUserEntity()).thenReturn(currentUser);
        when(userService.isAdmin(currentUser)).thenReturn(admin);
        if (owner.getId().equals(currentUser.getId()) || admin) {
            when(reviewRepository.getRatingStatsByProductId(product.getId())).thenReturn(stats);
        }
        return review;
    }

    private DataIntegrityViolationException dataIntegrityViolation(String constraintName) {
        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "constraint violation", new SQLException("duplicate key"), constraintName);
        return new DataIntegrityViolationException("save failed", new RuntimeException("wrapped", constraintViolation));
    }

    private Product product(UUID id) {
        Product product = new Product("Product", "product-" + id, "SKU-" + id, "Description", BigDecimal.TEN, 10,
                new Category("Category", "category-" + id, "Description"));
        setEntityField(product, "id", id);
        return product;
    }

    private User user(UUID id, String firstName, String lastName) {
        User user = new User(firstName.toLowerCase() + "@example.com", "encoded", firstName, lastName);
        setEntityField(user, "id", id);
        return user;
    }

    private ProductReview review(UUID id, Product product, User user, int rating, String comment,
            LocalDateTime createdAt) {
        ProductReview review = new ProductReview(product, user, rating, comment);
        setEntityField(review, "id", id);
        setEntityField(review, "createdAt", createdAt);
        return review;
    }

    private void setEntityField(Object target, String fieldName, Object value) {
        ReflectionTestUtils.setField(target, fieldName, value);
    }
}
