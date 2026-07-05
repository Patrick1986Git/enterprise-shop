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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Users", description = "Admin-only user management endpoints.")
public class AdminUserController {

	private final UserService service;

	public AdminUserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
	        operationId = "getUsers",
	        summary = "List users (admin-only)",
	        security = @SecurityRequirement(name = "bearerAuth")
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Users returned successfully."),
			@ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
			@ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError")
	})
	public Page<UserResponseDTO> getUsers(@PageableDefault(size = 20) Pageable pageable) {
		return service.findAll(pageable);
	}

	@GetMapping("/{id}")
	@Operation(
	        operationId = "getUserById",
	        summary = "Get user details by ID (admin-only)",
	        security = @SecurityRequirement(name = "bearerAuth")
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "User found."),
			@ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
			@ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
			@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
	})
	public UserResponseDTO getUserById(
			@Parameter(description = "User identifier.")
			@PathVariable UUID id) {
		return service.findById(id);
	}

	@PutMapping("/{id}")
	@Operation(
	        operationId = "updateUser",
	        summary = "Update a user (admin-only)",
	        security = @SecurityRequirement(name = "bearerAuth")
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "User updated successfully."),
			@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequestError"),
			@ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
			@ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
			@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError"),
			@ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictError")
	})
	public UserResponseDTO updateUser(
			@Parameter(description = "User identifier.")
			@PathVariable UUID id,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "User first and last name data to update."
			)
			@Valid @RequestBody UserUpdateDTO dto) {
		return service.update(id, dto);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
	        operationId = "deleteUser",
	        summary = "Delete a user (admin-only)",
	        security = @SecurityRequirement(name = "bearerAuth")
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "User deleted successfully."),
			@ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError"),
			@ApiResponse(responseCode = "403", ref = "#/components/responses/ForbiddenError"),
			@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundError")
	})
	public void deleteUser(
			@Parameter(description = "User identifier.")
			@PathVariable UUID id) {
		service.delete(id);
	}
}
