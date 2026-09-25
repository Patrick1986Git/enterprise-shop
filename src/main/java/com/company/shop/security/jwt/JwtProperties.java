package com.company.shop.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@ConfigurationProperties(prefix = "security.jwt")
@Validated
public class JwtProperties {

	@NotBlank
	private final String secret;
	@NotBlank
	private final String keyId;
	private final String previousSecret;
	private final String previousKeyId;
	@Positive
	private final long expiration;

	public JwtProperties(String secret, String keyId, String previousSecret, String previousKeyId,
			long expiration) {
		this.secret = secret;
		this.keyId = keyId;
		this.previousSecret = previousSecret;
		this.previousKeyId = previousKeyId;
		this.expiration = expiration;
	}

	public String getSecret() {
		return secret;
	}

	public String getKeyId() {
		return keyId;
	}

	public String getPreviousSecret() {
		return previousSecret;
	}

	public String getPreviousKeyId() {
		return previousKeyId;
	}

	public long getExpiration() {
		return expiration;
	}
}
