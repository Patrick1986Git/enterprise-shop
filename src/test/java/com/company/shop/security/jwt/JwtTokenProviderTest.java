package com.company.shop.security.jwt;

import static com.company.shop.security.SecurityConstants.ROLE_ADMIN;
import static com.company.shop.security.SecurityConstants.ROLE_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtTokenProviderTest {

    private static final byte[] KEY_BYTES = "01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OTHER_KEY_BYTES = "abcdefghijklmnopqrstuvwxyzABCDEF".getBytes(StandardCharsets.US_ASCII);
    private static final String SECRET = Base64.getEncoder().encodeToString(KEY_BYTES);
    private static final String OTHER_SECRET = Base64.getEncoder().encodeToString(OTHER_KEY_BYTES);
    private static final String USERNAME = "john.doe@example.com";

    @Test
    void generateToken_shouldCreateValidTokenWithConfiguredLifetimeUsernameAndRoles() {
        JwtTokenProvider provider = tokenProvider(SECRET, 60_000L);
        Authentication authentication = authentication(USERNAME, ROLE_USER, ROLE_ADMIN);
        long beforeIssuance = System.currentTimeMillis();

        String token = provider.generateToken(authentication);
        long afterIssuance = System.currentTimeMillis();
        Jws<Claims> claims = parseWith(KEY_BYTES, token);
        String rolesClaim = provider.getRoles(token);

        assertThat(token).isNotBlank();
        assertThat(provider.validate(token)).isTrue();
        assertThat(provider.getUsername(token)).isEqualTo(USERNAME);
        assertThat(rolesFrom(rolesClaim)).containsExactlyInAnyOrder(ROLE_USER, ROLE_ADMIN);
        assertThat(claims.getPayload().getExpiration().getTime())
                .isBetween(beforeIssuance + 59_000L, afterIssuance + 60_000L);
        assertThat(claims.getHeader().getAlgorithm()).isEqualTo("HS256");
    }

    @Test
    void constructor_shouldDecodeConfiguredBase64TextAsSigningKeyBytes() {
        JwtTokenProvider provider = tokenProvider(SECRET, 60_000L);

        String token = provider.generateToken(authentication(USERNAME, ROLE_USER));

        assertThat(parseWith(KEY_BYTES, token).getPayload().getSubject()).isEqualTo(USERNAME);
        SecretKey legacyTextBytesKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Jwts.parser().verifyWith(legacyTextBytesKey).build().parseSignedClaims(token))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void generateToken_shouldStoreSingleAuthorityAsRolesClaim() {
        JwtTokenProvider provider = tokenProvider(60_000L);

        String token = provider.generateToken(authentication(USERNAME, ROLE_USER));

        assertThat(provider.getRoles(token)).isEqualTo(ROLE_USER);
    }

    @Test
    void validate_shouldRejectMalformedExpiredAndDifferentlySignedTokens() {
        JwtTokenProvider provider = tokenProvider(60_000L);
        String expiredToken = Jwts.builder()
                .subject(USERNAME)
                .expiration(new Date(System.currentTimeMillis() - 1_000L))
                .signWith(Keys.hmacShaKeyFor(KEY_BYTES))
                .compact();
        String otherToken = tokenProvider(OTHER_SECRET, 60_000L)
                .generateToken(authentication(USERNAME, ROLE_USER));

        assertThat(provider.validate("not-a-jwt-token")).isFalse();
        assertThat(provider.validate(expiredToken)).isFalse();
        assertThat(provider.validate(otherToken)).isFalse();
        assertThat(provider.validate(null)).isFalse();
        assertThat(provider.validate("")).isFalse();
        assertThat(provider.validate("   ")).isFalse();
    }

    @Test
    void constructor_shouldRejectMissingBlankMalformedWeakAndNonAsciiSecretsWithoutDisclosure() {
        assertInvalidSecret(null, "non-blank");
        assertInvalidSecret("   ", "non-blank");
        assertInvalidSecret("not-valid-base64!", "valid RFC 4648 Base64");
        assertInvalidSecret(Base64.getEncoder().encodeToString(new byte[31]), "at least 256 bits");
        assertInvalidSecret("żółć", "valid RFC 4648 Base64");
    }

    @Test
    void constructor_shouldRejectNonPositiveAndOverflowingExpiration() {
        assertInvalidExpiration(0, "positive");
        assertInvalidExpiration(-1, "positive");
        assertInvalidExpiration(Long.MAX_VALUE, "too large");
    }

    private void assertInvalidSecret(String secret, String expectedMessage) {
        assertThatThrownBy(() -> tokenProvider(secret, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(String.valueOf(secret)));
    }

    private void assertInvalidExpiration(long expiration, String expectedMessage) {
        assertThatThrownBy(() -> tokenProvider(SECRET, expiration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    private Jws<Claims> parseWith(byte[] keyBytes, String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(keyBytes))
                .build()
                .parseSignedClaims(token);
    }

    private JwtTokenProvider tokenProvider(long expiration) {
        return tokenProvider(SECRET, expiration);
    }

    private JwtTokenProvider tokenProvider(String secret, long expiration) {
        return new JwtTokenProvider(new JwtProperties(secret, expiration, 120_000L));
    }

    private Authentication authentication(String username, String... roles) {
        return new UsernamePasswordAuthenticationToken(
                username,
                "password",
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    private Set<String> rolesFrom(String rolesClaim) {
        return Arrays.stream(rolesClaim.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toSet());
    }
}
