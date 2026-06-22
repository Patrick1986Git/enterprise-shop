package com.company.shop.module.user.dto;

import com.company.shop.validation.annotation.PasswordMatches;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for registering a new user account.")
@PasswordMatches
public class RegisterRequestDTO {

	@Schema(
			description = "Email address for the new user account.",
			example = "user@example.com",
			requiredMode = Schema.RequiredMode.REQUIRED
	)
	@Email(message = "{validation.user.email.invalid}")
	@NotBlank(message = "{validation.user.email.required}")
	private String email;

	@Schema(
			description = "Password for the new account. Must be at least 8 characters.",
			example = "StrongPassword123!",
			format = "password",
			minLength = 8,
			requiredMode = Schema.RequiredMode.REQUIRED,
			accessMode = Schema.AccessMode.WRITE_ONLY
	)
	@NotBlank(message = "{validation.user.password.required}")
	@Size(min = 8, message = "{validation.user.password.size}")
	private String password;

	@Schema(
			description = "Repeated password used to confirm the account password.",
			example = "StrongPassword123!",
			format = "password",
			requiredMode = Schema.RequiredMode.REQUIRED,
			accessMode = Schema.AccessMode.WRITE_ONLY
	)
	@NotBlank(message = "{validation.user.password.confirmation.required}")
	private String passwordRepeat;

	@Schema(
	        description = "User first name.",
	        example = "Alex",
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotBlank(message = "{validation.user.firstName.required}")
	private String firstName;

	@Schema(
	        description = "User last name.",
	        example = "Morgan",
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotBlank(message = "{validation.user.lastName.required}")
	private String lastName;

	public RegisterRequestDTO() {
	}

	public RegisterRequestDTO(String email, String password, String passwordRepeat, String firstName, String lastName) {
		this.email = email;
		this.password = password;
		this.passwordRepeat = passwordRepeat;
		this.firstName = firstName;
		this.lastName = lastName;
	}

	public String getEmail() {
		return email;
	}

	public String getPassword() {
		return password;
	}

	public String getPasswordRepeat() {
		return passwordRepeat;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}
}
