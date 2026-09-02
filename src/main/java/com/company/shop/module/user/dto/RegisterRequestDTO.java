package com.company.shop.module.user.dto;

import com.company.shop.validation.annotation.PasswordMatches;
import com.company.shop.validation.annotation.Utf8Length;

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
			maxLength = 255,
			requiredMode = Schema.RequiredMode.REQUIRED
	)
	@Email(message = "{validation.user.email.invalid}")
	@NotBlank(message = "{validation.user.email.required}")
	@Size(max = 255, message = "{validation.user.email.size}")
	private String email;

	@Schema(
			description = "Password for the new account. Must be at least 8 characters.",
			example = "StrongPassword123!",
			format = "password",
			minLength = 8,
			maxLength = 72,
			requiredMode = Schema.RequiredMode.REQUIRED,
			accessMode = Schema.AccessMode.WRITE_ONLY
	)
	@NotBlank(message = "{validation.user.password.required}")
	@Size(min = 8, max = 72, message = "{validation.user.password.size}")
	@Utf8Length(max = 72, message = "{validation.user.password.maxSize}")
	private String password;

	@Schema(
			description = "Repeated password used to confirm the account password.",
			example = "StrongPassword123!",
			format = "password",
			maxLength = 72,
			requiredMode = Schema.RequiredMode.REQUIRED,
			accessMode = Schema.AccessMode.WRITE_ONLY
	)
	@NotBlank(message = "{validation.user.password.confirmation.required}")
	@Utf8Length(max = 72, message = "{validation.user.password.confirmation.size}")
	private String passwordRepeat;

	@Schema(
	        description = "User first name.",
	        example = "Alex",
	        maxLength = 100,
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotBlank(message = "{validation.user.firstName.required}")
	@Size(max = 100, message = "{validation.user.firstName.size}")
	private String firstName;

	@Schema(
	        description = "User last name.",
	        example = "Morgan",
	        maxLength = 100,
	        requiredMode = Schema.RequiredMode.REQUIRED
	)
	@NotBlank(message = "{validation.user.lastName.required}")
	@Size(max = 100, message = "{validation.user.lastName.size}")
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
