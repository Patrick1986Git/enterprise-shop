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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Publiczne endpointy kategorii.")
public class CategoryController {

	private final CategoryService service;

	public CategoryController(CategoryService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Lista kategorii")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Kategorie pobrane poprawnie.")
	})
	public Page<CategoryResponseDTO> getCategories(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		Pageable pageable = PageRequest.of(page, size);
		return service.findAll(pageable);
	}

	@GetMapping("/slug/{slug}")
	@Operation(summary = "Szczegóły kategorii po slug")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Kategoria znaleziona."),
			@ApiResponse(responseCode = "404", description = "Kategoria nie została znaleziona.")
	})
	public CategoryResponseDTO getCategoryBySlug(@PathVariable String slug) {
		return service.findBySlug(slug);
	}
}
