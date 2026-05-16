package com.company.shop.module.user.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.dto.UserUpdateDTO;
import com.company.shop.module.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Users", description = "Administracyjne endpointy użytkowników.")
public class AdminUserController {

	private final UserService service;

	public AdminUserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Lista użytkowników")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Użytkownicy pobrani poprawnie."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień.")
	})
	public Page<UserResponseDTO> getUsers(@PageableDefault(size = 20) Pageable pageable) {
		return service.findAll(pageable);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Szczegóły użytkownika po ID")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Użytkownik znaleziony."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "404", description = "Użytkownik nie został znaleziony.")
	})
	public UserResponseDTO getUserById(@PathVariable UUID id) {
		return service.findById(id);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Aktualizacja użytkownika")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Użytkownik zaktualizowany poprawnie."),
			@ApiResponse(responseCode = "400", description = "Nieprawidłowe dane żądania."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "404", description = "Użytkownik nie został znaleziony."),
			@ApiResponse(responseCode = "409", description = "Konflikt danych użytkownika.")
	})
	public UserResponseDTO updateUser(@PathVariable UUID id, @Valid @RequestBody UserUpdateDTO dto) {
		return service.update(id, dto);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Usunięcie użytkownika")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Użytkownik usunięty poprawnie."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji."),
			@ApiResponse(responseCode = "403", description = "Brak uprawnień."),
			@ApiResponse(responseCode = "404", description = "Użytkownik nie został znaleziony.")
	})
	public void deleteUser(@PathVariable UUID id) {
		service.delete(id);
	}
}
