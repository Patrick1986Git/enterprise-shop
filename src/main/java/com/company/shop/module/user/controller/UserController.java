package com.company.shop.module.user.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.user.dto.UserResponseDTO;
import com.company.shop.module.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Users", description = "Endpointy profilu aktualnie zalogowanego użytkownika.")
public class UserController {

	private final UserService service;

	public UserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Profil aktualnego użytkownika")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Profil użytkownika pobrany poprawnie."),
			@ApiResponse(responseCode = "401", description = "Brak autoryzacji.")
	})
	public UserResponseDTO getCurrentUser() {
		return service.getCurrentUserProfile();
	}
}
