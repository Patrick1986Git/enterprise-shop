package com.company.shop.module.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO używane do tworzenia nowego użytkownika (np. przez administratora).
 */
public class UserCreateDTO {

	@Email(message = "{validation.user.email.invalid}")
	@NotBlank(message = "{validation.user.email.required}")
	private String email;

	@NotBlank(message = "{validation.user.password.required}")
	@Size(min = 8, message = "{validation.user.password.size}")
	private String password;

	@NotBlank(message = "{validation.user.firstName.required}")
	@Size(max = 100, message = "{validation.user.firstName.size}")
	private String firstName;

	@NotBlank(message = "{validation.user.lastName.required}")
	@Size(max = 100, message = "{validation.user.lastName.size}")
	private String lastName;

	// Pusty konstruktor - wymagany przez bibliotekę Jackson do deserializacji JSON
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