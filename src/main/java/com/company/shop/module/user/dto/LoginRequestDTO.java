package com.company.shop.module.user.dto;

import com.company.shop.validation.annotation.Utf8Length;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for user login.")
public class LoginRequestDTO {

	@Schema(
			description = "User email address used as the login identifier.",
			example = "user@example.com",
			maxLength = 255,
			requiredMode = Schema.RequiredMode.REQUIRED
	)
	@Email(message = "{validation.user.email.invalid}")
	@NotBlank(message = "{validation.user.email.required}")
	@Size(max = 255, message = "{validation.user.email.size}")
	private String email;

	@Schema(
			description = "User password. Must not exceed 72 UTF-8 bytes.",
			example = "StrongPassword123!",
			format = "password",
			maxLength = 72,
			requiredMode = Schema.RequiredMode.REQUIRED,
			accessMode = Schema.AccessMode.WRITE_ONLY
	)
	@NotBlank(message = "{validation.user.password.required}")
	@Utf8Length(max = 72, message = "{validation.user.password.maxSize}")
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
