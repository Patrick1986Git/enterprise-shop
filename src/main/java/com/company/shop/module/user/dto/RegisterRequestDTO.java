package com.company.shop.module.user.dto;

import com.company.shop.validation.annotation.PasswordMatches;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@PasswordMatches
public class RegisterRequestDTO {

	@Email(message = "{validation.user.email.invalid}")
	@NotBlank(message = "{validation.user.email.required}")
	private String email;

	@NotBlank(message = "{validation.user.password.required}")
	@Size(min = 8, message = "{validation.user.password.size}")
	private String password;

	@NotBlank(message = "{validation.user.password.confirmation.required}")
	private String passwordRepeat;

	@NotBlank(message = "{validation.user.firstName.required}")
	private String firstName;

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
