package com.company.shop.module.product.service;

import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.product.dto.ProductReviewRequestDTO;
import com.company.shop.module.product.dto.ProductReviewResponseDTO;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.entity.ProductReview;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.exception.ProductReviewAccessDeniedException;
import com.company.shop.module.product.exception.ProductReviewAlreadyExistsException;
import com.company.shop.module.product.exception.ProductReviewNotFoundException;
import com.company.shop.module.product.exception.ProductReviewCountInvalidException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.product.repository.ProductReviewRepository;
import com.company.shop.module.product.repository.RatingStats;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.service.UserService;

@Service
@Transactional
public class ProductReviewServiceImpl implements ProductReviewService {

    private static final String USER_PRODUCT_REVIEW_UNIQUE_CONSTRAINT = "uk_user_product_review";

    private final ProductReviewRepository reviewRepo;
    private final ProductRepository productRepo;
    private final UserService userService;

    public ProductReviewServiceImpl(ProductReviewRepository reviewRepo, ProductRepository productRepo, UserService userService) {
        this.reviewRepo = reviewRepo;
        this.productRepo = productRepo;
        this.userService = userService;
    }

    @Override
    public ProductReviewResponseDTO addReview(ProductReviewRequestDTO dto) {
        User user = userService.getCurrentUserEntity();

        if (reviewRepo.existsByProductIdAndUserId(dto.productId(), user.getId())) {
            throw new ProductReviewAlreadyExistsException(dto.productId());
        }

        Product product = getProductOrThrow(dto.productId());
        ProductReview saved;
        try {
            saved = reviewRepo.saveAndFlush(new ProductReview(product, user, dto.rating(), dto.comment()));
        } catch (DataIntegrityViolationException ex) {
            if (isUserProductReviewUniqueConstraintViolation(ex)) {
                throw new ProductReviewAlreadyExistsException(dto.productId());
            }
            throw ex;
        }

        updateProductRatingStats(product);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductReviewResponseDTO> getProductReviews(UUID productId, Pageable pageable) {
        getProductOrThrow(productId);
        return reviewRepo.findByProductId(productId, pageable).map(this::mapToResponse);
    }

    @Override
    public void deleteReview(UUID reviewId) {
        ProductReview review = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new ProductReviewNotFoundException(reviewId));

        User currentUser = userService.getCurrentUserEntity();
        boolean isOwner = review.getUser().getId().equals(currentUser.getId());
        boolean isAdmin = userService.isAdmin(currentUser);

        if (!isOwner && !isAdmin) {
            throw new ProductReviewAccessDeniedException();
        }

        Product product = review.getProduct();
        review.delete();
        updateProductRatingStats(product);
    }

    private Product getProductOrThrow(UUID productId) {
        return productRepo.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private boolean isUserProductReviewUniqueConstraintViolation(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof ConstraintViolationException constraintViolationException
                    && USER_PRODUCT_REVIEW_UNIQUE_CONSTRAINT.equalsIgnoreCase(
                            constraintViolationException.getConstraintName())) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void updateProductRatingStats(Product product) {
        RatingStats stats = reviewRepo.getRatingStatsByProductId(product.getId());

        long count = stats != null ? stats.reviewCount() : 0L;
        if (count > Integer.MAX_VALUE) {
            throw new ProductReviewCountInvalidException("Review count exceeds integer range for product: " + product.getId());
        }

        double avg = (stats != null && stats.averageRating() != null) ? stats.averageRating() : 0.0;
        product.updateRatings(avg, (int) count);
        productRepo.save(product);
    }

    private ProductReviewResponseDTO mapToResponse(ProductReview review) {
        return new ProductReviewResponseDTO(review.getId(),
                review.getUser().getFirstName() + " " + review.getUser().getLastName(),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt());
    }
}
