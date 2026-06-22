package com.company.shop.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for updating user profile details.")
public class UserUpdateDTO {

	@Schema(description = "Updated user first name.", example = "Alex", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "{validation.user.firstName.required}")
	@Size(max = 100, message = "{validation.user.firstName.size}")
	private String firstName;

	@Schema(description = "Updated user last name.", example = "Morgan", maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "{validation.user.lastName.required}")
	@Size(max = 100, message = "{validation.user.lastName.size}")
	private String lastName;

	public UserUpdateDTO() { }
	public UserUpdateDTO(String firstName, String lastName) { this.firstName = firstName; this.lastName = lastName; }
	public String getFirstName() { return firstName; }
	public String getLastName() { return lastName; }
}
