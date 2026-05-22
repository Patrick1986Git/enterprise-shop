package com.company.shop.module.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserUpdateDTO {

	@NotBlank(message = "{validation.user.firstName.required}")
	@Size(max = 100, message = "{validation.user.firstName.size}")
	private String firstName;

	@NotBlank(message = "{validation.user.lastName.required}")
	@Size(max = 100, message = "{validation.user.lastName.size}")
	private String lastName;

	// Pusty konstruktor dla biblioteki Jackson
	public UserUpdateDTO() {
	}

	public UserUpdateDTO(String firstName, String lastName) {
		this.firstName = firstName;
		this.lastName = lastName;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}
}