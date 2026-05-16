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
@Tag(name = "Product Reviews", description = "Endpointy opinii o produktach.")
public class ProductReviewController {

	private final ProductReviewService reviewService;

	public ProductReviewController(ProductReviewService reviewService) {
		this.reviewService = reviewService;
	}

	@PostMapping("/reviews")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Dodanie opinii do produktu")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Opinia dodana poprawnie."),
			@ApiResponse(responseCode = "400", description = "Nieprawidłowe dane żądania."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "404", description = "Produkt nie został znaleziony."),
			@ApiResponse(responseCode = "409", description = "Użytkownik już ocenił ten produkt.")
	})
	public ProductReviewResponseDTO createReview(@Valid @RequestBody ProductReviewRequestDTO dto) {
		return reviewService.addReview(dto);
	}

	@GetMapping("/products/{productId}/reviews")
	@Operation(summary = "Lista opinii produktu")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Opinie pobrane poprawnie."),
			@ApiResponse(responseCode = "404", description = "Produkt nie został znaleziony.")
	})
	public Page<ProductReviewResponseDTO> getProductReviews(@PathVariable UUID productId, Pageable pageable) {
		return reviewService.getProductReviews(productId, pageable);
	}

	@DeleteMapping("/reviews/{reviewId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Usunięcie opinii")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Opinia usunięta poprawnie."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "404", description = "Opinia nie została znaleziona.")
	})
	public void deleteReview(@PathVariable UUID reviewId) {
		reviewService.deleteReview(reviewId);
	}
}
