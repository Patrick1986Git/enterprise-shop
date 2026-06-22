package com.company.shop.module.category.controller;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.category.dto.CategoryResponseDTO;
import com.company.shop.module.category.service.CategoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Public category endpoints.")
public class CategoryController {

	private final CategoryService service;

	public CategoryController(CategoryService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
	        operationId = "getCategories",
	        summary = "List categories"
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Categories returned successfully.")
	})
	public Page<CategoryResponseDTO> getCategories(
			@Parameter(description = "Zero-based page index.")
			@RequestParam(defaultValue = "0") int page,
			@Parameter(description = "Number of categories to return per page.")
			@RequestParam(defaultValue = "20") int size) {
		Pageable pageable = PageRequest.of(page, size);
		return service.findAll(pageable);
	}

	@GetMapping("/slug/{slug}")
	@Operation(
	        operationId = "getCategoryBySlug",
	        summary = "Get category details by slug"
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Category found."),
			@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
	})
	public CategoryResponseDTO getCategoryBySlug(
			@Parameter(description = "URL-safe category slug.")
			@PathVariable String slug) {
		return service.findBySlug(slug);
	}
}
