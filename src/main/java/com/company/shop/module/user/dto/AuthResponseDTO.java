package com.company.shop.module.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO zwracane użytkownikowi po pomyślnym uwierzytelnieniu.
 */
@Schema(description = "Response payload returned after successful authentication.")
public class AuthResponseDTO {

	@Schema(description = "JWT access token issued for the authenticated user.", example = "eyJhbGciOi...",
			requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.READ_ONLY)
	private final String token;

	@Schema(description = "Authentication scheme used with the access token.", example = "Bearer",
			requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.READ_ONLY)
	private final String type = "Bearer";

	public AuthResponseDTO(String token) {
		this.token = token;
	}

	public String getToken() {
		return token;
	}

	public String getType() {
		return type;
	}
}
