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
@Tag(name = "Admin Categories", description = "Administracyjne zarządzanie kategoriami.")
public class AdminCategoryController {

	private final CategoryService service;

	public AdminCategoryController(CategoryService service) {
		this.service = service;
	}

	@GetMapping("/{id}")
	@Operation(summary = "Szczegóły kategorii po ID")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Kategoria znaleziona."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "404", description = "Kategoria nie została znaleziona.")
	})
	public CategoryResponseDTO getCategoryById(@PathVariable UUID id) {
		return service.findById(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Utworzenie kategorii")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Kategoria utworzona poprawnie."),
			@ApiResponse(responseCode = "400", description = "Nieprawidłowe dane żądania."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "409", description = "Konflikt danych kategorii.")
	})
	public CategoryResponseDTO createCategory(@Valid @RequestBody CategoryCreateDTO dto) {
		return service.create(dto);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Aktualizacja kategorii")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Kategoria zaktualizowana poprawnie."),
			@ApiResponse(responseCode = "400", description = "Nieprawidłowe dane żądania."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "404", description = "Kategoria nie została znaleziona."),
			@ApiResponse(responseCode = "409", description = "Konflikt danych kategorii.")
	})
	public CategoryResponseDTO updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryCreateDTO dto) {
		return service.update(id, dto);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Usunięcie kategorii")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Kategoria usunięta poprawnie."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "404", description = "Kategoria nie została znaleziona.")
	})
	public void deleteCategory(@PathVariable UUID id) {
		service.delete(id);
	}
}
