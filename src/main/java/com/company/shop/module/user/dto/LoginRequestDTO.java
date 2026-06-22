package com.company.shop.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload for user login.")
public class LoginRequestDTO {

	@Schema(description = "User email address used as the login identifier.", example = "user@example.com",
			requiredMode = Schema.RequiredMode.REQUIRED)
	@Email(message = "{validation.user.email.invalid}")
	@NotBlank(message = "{validation.user.email.required}")
	private String email;

	@Schema(description = "User password.", example = "StrongPassword123!", format = "password",
			requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY)
	@NotBlank(message = "{validation.user.password.required}")
	private String password;

	public LoginRequestDTO() {
	}

	public LoginRequestDTO(String email, String password) {
		this.email = email;
		this.password = password;
	}

	public String getEmail() {
		return email;
	}

	public String getPassword() {
		return password;
	}
}
