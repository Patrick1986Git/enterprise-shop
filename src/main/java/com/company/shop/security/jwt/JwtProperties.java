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
	@Positive
	private final long expiration;
	private final long refreshExpiration;

	public JwtProperties(String secret, long expiration, long refreshExpiration) {
		this.secret = secret;
		this.expiration = expiration;
		this.refreshExpiration = refreshExpiration;
	}

	public String getSecret() {
		return secret;
	}

	public long getExpiration() {
		return expiration;
	}

	public long getRefreshExpiration() {
		return refreshExpiration;
	}
}
