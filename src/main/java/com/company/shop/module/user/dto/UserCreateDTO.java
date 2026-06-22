package com.company.shop.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO używane do tworzenia nowego użytkownika (np. przez administratora).
 */
@Schema(description = "Request payload for creating a user account.")
public class UserCreateDTO {

	@Schema(description = "Email address for the user account.", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
	@Email(message = "{validation.user.email.invalid}")
	@NotBlank(message = "{validation.user.email.required}")
	private String email;

	@Schema(description = "Initial account password. Must be at least 8 characters.", example = "StrongPassword123!", format = "password", minLength = 8, requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY)
	@NotBlank(message = "{validation.user.password.required}")
	@Size(min = 8, message = "{validation.user.password.size}")
	private String password;

	@Schema(description = "User first name.", example = "Alex", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "{validation.user.firstName.required}")
	@Size(max = 100, message = "{validation.user.firstName.size}")
	private String firstName;

	@Schema(description = "User last name.", example = "Morgan", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "{validation.user.lastName.required}")
	@Size(max = 100, message = "{validation.user.lastName.size}")
	private String lastName;

	public UserCreateDTO() {
	}

	public UserCreateDTO(String email, String password, String firstName, String lastName) {
		this.email = email;
		this.password = password;
		this.firstName = firstName;
		this.lastName = lastName;
	}

	public String getEmail() {
		return email;
	}

	public String getPassword() {
		return password;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}
}
