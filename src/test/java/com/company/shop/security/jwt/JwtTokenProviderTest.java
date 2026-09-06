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
    private static final byte[] PREVIOUS_KEY_BYTES = "previous-key-material-32-bytes!!".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OTHER_KEY_BYTES = "abcdefghijklmnopqrstuvwxyzABCDEF".getBytes(StandardCharsets.US_ASCII);
    private static final String SECRET = Base64.getEncoder().encodeToString(KEY_BYTES);
    private static final String PREVIOUS_SECRET = Base64.getEncoder().encodeToString(PREVIOUS_KEY_BYTES);
    private static final String OTHER_SECRET = Base64.getEncoder().encodeToString(OTHER_KEY_BYTES);
    private static final String KEY_ID = "current-2026-09";
    private static final String PREVIOUS_KEY_ID = "previous-2026-08";
    private static final String USERNAME = "john.doe@example.com";

    @Test
    void generateToken_shouldCreateValidTokenWithConfiguredLifetimeUsernameRolesAndKeyId() {
        JwtTokenProvider provider = tokenProvider(SECRET, KEY_ID, null, null, 60_000L);
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
        assertThat(claims.getHeader().getKeyId()).isEqualTo(KEY_ID);
        assertThat(claims.getPayload().getExpiration().getTime())
                .isBetween(beforeIssuance + 59_000L, afterIssuance + 60_000L);
        assertThat(claims.getHeader().getAlgorithm()).isEqualTo("HS256");
    }

    @Test
    void constructor_shouldDecodeConfiguredBase64TextAsSigningKeyBytes() {
        JwtTokenProvider provider = tokenProvider(SECRET, KEY_ID, null, null, 60_000L);

        String token = provider.generateToken(authentication(USERNAME, ROLE_USER));

        assertThat(parseWith(KEY_BYTES, token).getPayload().getSubject()).isEqualTo(USERNAME);
        SecretKey legacyTextBytesKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Jwts.parser().verifyWith(legacyTextBytesKey).build().parseSignedClaims(token))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void validate_shouldKeepPhaseOneAndPhaseTwoReplicasMutuallyCompatible() {
        JwtTokenProvider phaseOne =
                tokenProvider(SECRET, KEY_ID, PREVIOUS_SECRET, PREVIOUS_KEY_ID, 60_000L);
        JwtTokenProvider phaseTwo =
                tokenProvider(PREVIOUS_SECRET, PREVIOUS_KEY_ID, SECRET, KEY_ID, 60_000L);

        String phaseOneToken = phaseOne.generateToken(authentication(USERNAME, ROLE_USER));
        String phaseTwoToken = phaseTwo.generateToken(authentication(USERNAME, ROLE_USER));

        assertThat(phaseOne.validate(phaseOneToken)).isTrue();
        assertThat(phaseOne.validate(phaseTwoToken)).isTrue();
        assertThat(phaseTwo.validate(phaseOneToken)).isTrue();
        assertThat(phaseTwo.validate(phaseTwoToken)).isTrue();
    }

    @Test
    void validate_shouldRejectUnknownRetiredAndSameIdDifferentKeyTokens() {
        JwtTokenProvider rolloverProvider =
                tokenProvider(SECRET, KEY_ID, PREVIOUS_SECRET, PREVIOUS_KEY_ID, 60_000L);
        JwtTokenProvider retiredProvider = tokenProvider(SECRET, KEY_ID, null, null, 60_000L);
        String unknownKeyToken = signedToken(KEY_BYTES, "unknown-key");
        String previousToken = signedToken(PREVIOUS_KEY_BYTES, PREVIOUS_KEY_ID);
        String sameIdDifferentKeyToken = signedToken(OTHER_KEY_BYTES, KEY_ID);

        assertThat(rolloverProvider.validate(unknownKeyToken)).isFalse();
        assertThat(retiredProvider.validate(previousToken)).isFalse();
        assertThat(rolloverProvider.validate(sameIdDifferentKeyToken)).isFalse();
    }

    @Test
    void validate_shouldImmediatelyRejectFormerActiveKeyWhenEmergencyRotationDoesNotRetainIt() {
        JwtTokenProvider oldProvider = tokenProvider(SECRET, KEY_ID, null, null, 60_000L);
        JwtTokenProvider emergencyProvider = tokenProvider(OTHER_SECRET, "emergency-2026-09", null, null, 60_000L);
        String oldToken = oldProvider.generateToken(authentication(USERNAME, ROLE_USER));

        assertThat(emergencyProvider.validate(oldToken)).isFalse();
    }

    @Test
    void validate_shouldBoundLegacyNoKidCompatibilityToPreRolloverConfiguration() {
        JwtTokenProvider initialRolloutProvider = tokenProvider(SECRET, KEY_ID, null, null, 60_000L);
        JwtTokenProvider rolloverProvider =
                tokenProvider(SECRET, KEY_ID, PREVIOUS_SECRET, PREVIOUS_KEY_ID, 60_000L);
        String legacyToken = signedToken(KEY_BYTES, null);

        assertThat(initialRolloutProvider.validate(legacyToken)).isTrue();
        assertThat(rolloverProvider.validate(legacyToken)).isFalse();
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
                .header().keyId(KEY_ID).and()
                .subject(USERNAME)
                .expiration(new Date(System.currentTimeMillis() - 1_000L))
                .signWith(Keys.hmacShaKeyFor(KEY_BYTES))
                .compact();
        String otherToken = tokenProvider(OTHER_SECRET, "other-key", null, null, 60_000L)
                .generateToken(authentication(USERNAME, ROLE_USER));

        assertThat(provider.validate("not-a-jwt-token")).isFalse();
        assertThat(provider.validate(expiredToken)).isFalse();
        assertThat(provider.validate(otherToken)).isFalse();
        assertThat(provider.validate(null)).isFalse();
        assertThat(provider.validate("")).isFalse();
        assertThat(provider.validate("   ")).isFalse();
    }

    @Test
    void constructor_shouldRejectInvalidKeyIdsAndPreviousKeyConfiguration() {
        assertThatThrownBy(() -> tokenProvider(SECRET, null, null, null, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.jwt.key-id");
        assertThatThrownBy(() -> tokenProvider(SECRET, " invalid ", null, null, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.jwt.key-id");
        assertThatThrownBy(() -> tokenProvider(SECRET, "a".repeat(65), null, null, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.jwt.key-id");
        assertThatThrownBy(() -> tokenProvider(SECRET, KEY_ID, PREVIOUS_SECRET, null, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
        assertThatThrownBy(() -> tokenProvider(SECRET, KEY_ID, null, PREVIOUS_KEY_ID, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
        assertThatThrownBy(() -> tokenProvider(SECRET, KEY_ID, PREVIOUS_SECRET, KEY_ID, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be different");
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
    void constructor_shouldRejectMalformedOrWeakPreviousSecretWithoutDisclosure() {
        assertInvalidPreviousSecret("previous-secret-must-not-leak!", "valid RFC 4648 Base64");
        assertInvalidPreviousSecret(Base64.getEncoder().encodeToString(new byte[31]), "at least 256 bits");
    }

    @Test
    void constructor_shouldRejectNonPositiveAndOverflowingExpiration() {
        assertInvalidExpiration(0, "positive");
        assertInvalidExpiration(-1, "positive");
        assertInvalidExpiration(Long.MAX_VALUE, "too large");
    }

    private void assertInvalidSecret(String secret, String expectedMessage) {
        assertThatThrownBy(() -> tokenProvider(secret, KEY_ID, null, null, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(String.valueOf(secret)));
    }

    private void assertInvalidPreviousSecret(String secret, String expectedMessage) {
        assertThatThrownBy(() -> tokenProvider(SECRET, KEY_ID, secret, PREVIOUS_KEY_ID, 60_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(String.valueOf(secret)));
    }

    private void assertInvalidExpiration(long expiration, String expectedMessage) {
        assertThatThrownBy(() -> tokenProvider(SECRET, KEY_ID, null, null, expiration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    private String signedToken(byte[] keyBytes, String keyId) {
        var builder = Jwts.builder();
        if (keyId != null) {
            builder.header().keyId(keyId).and();
        }
        return builder
                .subject(USERNAME)
                .claim("roles", ROLE_USER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(keyBytes))
                .compact();
    }

    private Jws<Claims> parseWith(byte[] keyBytes, String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(keyBytes))
                .build()
                .parseSignedClaims(token);
    }

    private JwtTokenProvider tokenProvider(long expiration) {
        return tokenProvider(SECRET, KEY_ID, null, null, expiration);
    }

    private JwtTokenProvider tokenProvider(String secret, String keyId, String previousSecret,
                                           String previousKeyId, long expiration) {
        return new JwtTokenProvider(
                new JwtProperties(secret, keyId, previousSecret, previousKeyId, expiration, 120_000L));
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
