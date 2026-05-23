package com.company.shop.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.company.shop.module.user.dto.AuthResponseDTO;
import com.company.shop.module.user.dto.LoginRequestDTO;
import com.company.shop.module.user.dto.RegisterRequestDTO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Public authentication endpoints for user registration and login.")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	@Operation(summary = "Authenticate a user and return a JWT access token")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Authentication successful."),
			@ApiResponse(responseCode = "401", description = "Invalid credentials.")
	})
	public AuthResponseDTO login(@Valid @RequestBody LoginRequestDTO request) {
		return authService.login(request);
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Register a new user account")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "User account registered successfully."),
			@ApiResponse(responseCode = "409", description = "A user with the provided email already exists.")
	})
	public void register(@Valid @RequestBody RegisterRequestDTO request) {
		authService.register(request);
	}
}
