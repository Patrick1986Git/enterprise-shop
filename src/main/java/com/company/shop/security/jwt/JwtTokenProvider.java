/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.security.jwt;

import java.util.Base64;
import java.util.Date;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

/**
 * Core security component responsible for the lifecycle of JSON Web Tokens (JWT).
 * <p>
 * This provider handles the issuance, cryptographic verification, and extraction 
 * of user identity and authorization claims using the JJWT library (v0.13.0).
 * It utilizes HMAC-SHA algorithms for secure token signing.
 * </p>
 *
 * @since 1.0.0
 */
@Component
public class JwtTokenProvider {

    private final JwtProperties properties;
    private final SecretKey key;

    /**
     * Constructs the provider and initializes the cryptographic key.
     *
     * @param properties configuration properties containing the secret and expiration limits.
     */
    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.key = signingKey(properties.getSecret());
        validateExpiration(properties.getExpiration());
    }

    /**
     * Generates a signed JWT for an authenticated principal.
     * <p>
     * Encodes user roles as a custom claim "roles" to facilitate stateless 
     * authorization in subsequent requests.
     * </p>
     *
     * @param authentication the principal's authentication details.
     * @return a compact, URL-safe JWT string.
     */
    public String generateToken(Authentication authentication) {
        Date now = new Date();
        Date expiry = new Date(Math.addExact(now.getTime(), properties.getExpiration()));

        String authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        return Jwts.builder()
                .subject(authentication.getName())
                .claim("roles", authorities)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * Extracts the subject (username) from a verified JWT.
     *
     * @param token the JWT string.
     * @return the principal's username.
     */
    public String getUsername(String token) {
        return parseClaims(token).getPayload().getSubject();
    }

    /**
     * Extracts the custom "roles" claim from a verified JWT.
     *
     * @param token the JWT string.
     * @return a comma-separated string of authorities.
     */
    public String getRoles(String token) {
        return parseClaims(token).getPayload().get("roles", String.class);
    }

    /**
     * Performs cryptographic and temporal validation of the provided token.
     *
     * @param token the JWT string to validate.
     * @return {@code true} if the token is authentic and not expired; {@code false} otherwise.
     */
    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // In enterprise environments, consider logging specific causes:
            // e.g., ExpiredJwtException, SignatureException
            return false;
        }
    }

    /**
     * Internal helper to parse and verify the JWT signature.
     * <p>
     * Utilizes the new JJWT 0.13.0 fluent API for signature verification.
     * </p>
     *
     * @param token the raw JWT string.
     * @return verified {@link Jws} claims.
     */
    private Jws<Claims> parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
    }

    private static SecretKey signingKey(String encodedSecret) {
        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalStateException("security.jwt.secret must be a non-blank Base64 value");
        }

        final byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("security.jwt.secret must be valid RFC 4648 Base64", exception);
        }

        try {
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (WeakKeyException exception) {
            throw new IllegalStateException(
                    "security.jwt.secret must encode at least 256 bits of key material", exception);
        }
    }

    private static void validateExpiration(long expiration) {
        if (expiration <= 0) {
            throw new IllegalStateException("security.jwt.expiration must be positive");
        }
        try {
            Math.addExact(System.currentTimeMillis(), expiration);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("security.jwt.expiration is too large", exception);
        }
    }
}
