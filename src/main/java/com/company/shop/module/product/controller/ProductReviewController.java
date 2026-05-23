package com.company.shop.module.product.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.product.dto.ProductReviewRequestDTO;
import com.company.shop.module.product.dto.ProductReviewResponseDTO;
import com.company.shop.module.product.service.ProductReviewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Product Reviews", description = "Product review endpoints.")
public class ProductReviewController {

	private final ProductReviewService reviewService;

	public ProductReviewController(ProductReviewService reviewService) {
		this.reviewService = reviewService;
	}

	@PostMapping("/reviews")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Create a product review")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Review created successfully."),
			@ApiResponse(responseCode = "400", description = "Invalid request payload."),
			@ApiResponse(responseCode = "401", description = "Unauthorized."),
			@ApiResponse(responseCode = "404", description = "Product not found."),
			@ApiResponse(responseCode = "409", description = "User has already reviewed this product.")
	})
	public ProductReviewResponseDTO createReview(@Valid @RequestBody ProductReviewRequestDTO dto) {
		return reviewService.addReview(dto);
	}

	@GetMapping("/products/{productId}/reviews")
	@Operation(summary = "List product reviews")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Reviews returned successfully."),
			@ApiResponse(responseCode = "404", description = "Product not found.")
	})
	public Page<ProductReviewResponseDTO> getProductReviews(@PathVariable UUID productId, Pageable pageable) {
		return reviewService.getProductReviews(productId, pageable);
	}

	@DeleteMapping("/reviews/{reviewId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Delete a product review")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Review deleted successfully."),
			@ApiResponse(responseCode = "401", description = "Unauthorized."),
			@ApiResponse(responseCode = "403", description = "Forbidden."),
			@ApiResponse(responseCode = "404", description = "Review not found.")
	})
	public void deleteReview(@PathVariable UUID reviewId) {
		reviewService.deleteReview(reviewId);
	}
}
