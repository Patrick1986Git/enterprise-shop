package com.company.shop.module.user.dto;

import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO służące do przesyłania danych użytkownika do klienta (Frontend).
 * Bezpiecznie pomija wrażliwe dane, takie jak hasło.
 */
@Schema(description = "Response payload containing user account details.")
public class UserResponseDTO {

	@Schema(description = "Unique user identifier.", example = "11111111-1111-1111-1111-111111111111", requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.READ_ONLY)
	private final UUID id;
	@Schema(description = "User email address.", example = "user@example.com", requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.READ_ONLY)
	private final String email;
	@Schema(description = "User first name.", example = "Alex", requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.READ_ONLY)
	private final String firstName;
	@Schema(description = "User last name.", example = "Morgan", requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.READ_ONLY)
	private final String lastName;
	@Schema(description = "Role names assigned to the user.", example = "[\"ROLE_USER\"]", requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.READ_ONLY)
	private final Set<String> roles;

	public UserResponseDTO(UUID id, String email, String firstName, String lastName, Set<String> roles) {
		this.id = id;
		this.email = email;
		this.firstName = firstName;
		this.lastName = lastName;
		this.roles = roles;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public Set<String> getRoles() {
		return roles;
	}
}
