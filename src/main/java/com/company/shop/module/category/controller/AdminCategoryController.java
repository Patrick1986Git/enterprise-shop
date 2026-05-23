package com.company.shop.module.category.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.category.dto.CategoryCreateDTO;
import com.company.shop.module.category.dto.CategoryResponseDTO;
import com.company.shop.module.category.service.CategoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Categories", description = "Admin-only category management endpoints.")
public class AdminCategoryController {

	private final CategoryService service;

	public AdminCategoryController(CategoryService service) {
		this.service = service;
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get category by ID (admin-only)")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Category found."),
			@ApiResponse(responseCode = "401", description = "Unauthorized."),
			@ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
			@ApiResponse(responseCode = "404", description = "Category not found.")
	})
	public CategoryResponseDTO getCategoryById(@PathVariable UUID id) {
		return service.findById(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a category (admin-only)")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Category created successfully."),
			@ApiResponse(responseCode = "400", description = "Invalid request payload."),
			@ApiResponse(responseCode = "401", description = "Unauthorized."),
			@ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
			@ApiResponse(responseCode = "409", description = "Category data conflict.")
	})
	public CategoryResponseDTO createCategory(@Valid @RequestBody CategoryCreateDTO dto) {
		return service.create(dto);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Update a category (admin-only)")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Category updated successfully."),
			@ApiResponse(responseCode = "400", description = "Invalid request payload."),
			@ApiResponse(responseCode = "401", description = "Unauthorized."),
			@ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
			@ApiResponse(responseCode = "404", description = "Category not found."),
			@ApiResponse(responseCode = "409", description = "Category data conflict.")
	})
	public CategoryResponseDTO updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryCreateDTO dto) {
		return service.update(id, dto);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete a category (admin-only)")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Category deleted successfully."),
			@ApiResponse(responseCode = "401", description = "Unauthorized."),
			@ApiResponse(responseCode = "403", description = "Forbidden (admin role required)."),
			@ApiResponse(responseCode = "404", description = "Category not found.")
	})
	public void deleteCategory(@PathVariable UUID id) {
		service.delete(id);
	}
}
