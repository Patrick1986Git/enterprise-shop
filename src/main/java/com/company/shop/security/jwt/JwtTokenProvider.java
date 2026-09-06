/*
 * Copyright (c) 2026 Your Company Name. All rights reserved.
 *
 * This software is the confidential and proprietary information of Your Company Name.
 * You shall not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into with Your Company.
 */

package com.company.shop.security.jwt;

import java.security.Key;
import java.util.Base64;
import java.util.Date;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

/**
 * Core security component responsible for the lifecycle of JSON Web Tokens (JWT).
 * <p>
 * This provider handles issuance, cryptographic verification, and extraction of
 * user identity and authorization claims using JJWT 0.13.0. New tokens are
 * signed with one active HMAC key and carry its configured {@code kid}. During
 * a bounded rotation window, verification may also accept one explicitly
 * configured previous key.
 * </p>
 *
 * @since 1.0.0
 */
@Component
public class JwtTokenProvider {

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final JwtProperties properties;
    private final String activeKeyId;
    private final SecretKey activeKey;
    private final String previousKeyId;
    private final SecretKey previousKey;
    private final JwtParser parser;

    /**
     * Constructs the provider, validates the bounded key set, and initializes
     * a JWS-specific key locator.
     *
     * @param properties configuration containing active/previous key material
     *                   and expiration limits.
     */
    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        this.activeKeyId = validateKeyId("security.jwt.key-id", properties.getKeyId());
        this.activeKey = signingKey("security.jwt.secret", properties.getSecret());

        PreviousKey configuredPreviousKey = previousKey(properties, activeKeyId);
        this.previousKeyId = configuredPreviousKey.keyId();
        this.previousKey = configuredPreviousKey.key();

        validateExpiration(properties.getExpiration());
        this.parser = Jwts.parser()
                .keyLocator(new LocatorAdapter<Key>() {
                    @Override
                    protected Key locate(JwsHeader header) {
                        return locateVerificationKey(header.getKeyId());
                    }
                })
                .build();
    }

    /**
     * Generates a signed JWT for an authenticated principal.
     * <p>
     * Encodes user roles as a custom claim {@code roles} and writes the
     * non-secret active key identifier to the protected {@code kid} header.
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
                .header()
                    .keyId(activeKeyId)
                    .and()
                .subject(authentication.getName())
                .claim("roles", authorities)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(activeKey)
                .compact();
    }

    /**
     * Extracts the subject from a cryptographically verified JWT.
     *
     * @param token the JWT string.
     * @return the principal's username.
     */
    public String getUsername(String token) {
        return parseClaims(token).getPayload().getSubject();
    }

    /**
     * Extracts the custom {@code roles} claim from a cryptographically verified JWT.
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
            return false;
        }
    }

    /**
     * Parses and verifies a signed claims JWS. JJWT resolves the verification
     * key from the protected JWS header before claims are returned.
     *
     * @param token the raw JWT string.
     * @return verified claims.
     */
    private Jws<Claims> parseClaims(String token) {
        return parser.parseSignedClaims(token);
    }

    private Key locateVerificationKey(String keyId) {
        if (keyId == null) {
            return previousKey == null ? activeKey : null;
        }
        if (activeKeyId.equals(keyId)) {
            return activeKey;
        }
        if (previousKey != null && previousKeyId.equals(keyId)) {
            return previousKey;
        }
        return null;
    }

    private static PreviousKey previousKey(JwtProperties properties, String activeKeyId) {
        boolean hasPreviousKeyId = properties.getPreviousKeyId() != null && !properties.getPreviousKeyId().isBlank();
        boolean hasPreviousSecret = properties.getPreviousSecret() != null && !properties.getPreviousSecret().isBlank();

        if (hasPreviousKeyId != hasPreviousSecret) {
            throw new IllegalStateException(
                    "security.jwt.previous-key-id and security.jwt.previous-secret must be configured together");
        }
        if (!hasPreviousKeyId) {
            return new PreviousKey(null, null);
        }

        String previousKeyId = validateKeyId("security.jwt.previous-key-id", properties.getPreviousKeyId());
        if (activeKeyId.equals(previousKeyId)) {
            throw new IllegalStateException("security.jwt.key-id and security.jwt.previous-key-id must be different");
        }

        return new PreviousKey(
                previousKeyId,
                signingKey("security.jwt.previous-secret", properties.getPreviousSecret()));
    }

    private static String validateKeyId(String propertyName, String keyId) {
        if (keyId == null || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalStateException(
                    propertyName + " must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        }
        return keyId;
    }

    private static SecretKey signingKey(String propertyName, String encodedSecret) {
        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalStateException(propertyName + " must be a non-blank Base64 value");
        }

        final byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(propertyName + " must be valid RFC 4648 Base64", exception);
        }

        try {
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (WeakKeyException exception) {
            throw new IllegalStateException(
                    propertyName + " must encode at least 256 bits of key material", exception);
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

    private record PreviousKey(String keyId, SecretKey key) {
    }
}
