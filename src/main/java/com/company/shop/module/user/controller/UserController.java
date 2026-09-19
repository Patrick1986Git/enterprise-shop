package com.company.shop.module.user.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.dto.PasswordChangeRequestDTO;
import com.company.shop.module.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Users", description = "Endpoints for the authenticated user profile.")
public class UserController {

	private final UserService service;

	public UserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(
	        operationId = "getCurrentUser",
	        summary = "Get the authenticated user profile",
	        security = @SecurityRequirement(name = "bearerAuth")
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "User profile returned successfully."),
			@ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError")
	})
	public UserResponseDTO getCurrentUser() {
		return service.getCurrentUserProfile();
	}

	@PutMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(operationId = "changeCurrentUserPassword", summary = "Change the authenticated user's password",
			security = @SecurityRequirement(name = "bearerAuth"))
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Password changed; previously issued access tokens are invalid."),
			@ApiResponse(responseCode = "400", ref = "#/components/responses/BadRequestError"),
			@ApiResponse(responseCode = "401", ref = "#/components/responses/UnauthorizedError")
	})
	public void changePassword(@Valid @RequestBody PasswordChangeRequestDTO request) {
		service.changeCurrentUserPassword(request);
	}
}
