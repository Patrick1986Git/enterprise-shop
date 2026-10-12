package com.company.shop.module.user.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.user.entity.PasswordRecoveryChallenge;

public interface PasswordRecoveryChallengeRepository extends JpaRepository<PasswordRecoveryChallenge, UUID> {
    @Query(value = "SELECT user_id FROM password_recovery_challenges WHERE id = :id", nativeQuery = true)
    Optional<UUID> findTargetId(@Param("id") UUID id);

    @Query(value = "SELECT * FROM password_recovery_challenges WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<PasswordRecoveryChallenge> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
    Instant currentDatabaseTime();

    @Modifying
    @Query(value = """
            UPDATE password_recovery_challenges SET revoked_at = clock_timestamp()
            WHERE user_id = :userId AND consumed_at IS NULL AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeOpen(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            WITH observation AS MATERIALIZED (SELECT clock_timestamp() AS observed)
            INSERT INTO password_recovery_challenges
                (id, user_id, format_version, verifier, issued_credential_version, created_at, expires_at)
            SELECT :id, :userId, 1, :verifier, :version, observed, observed + INTERVAL '15 minutes'
            FROM observation
            """, nativeQuery = true)
    int insertChallenge(@Param("id") UUID id, @Param("userId") UUID userId,
            @Param("verifier") byte[] verifier, @Param("version") long version);

    @Modifying
    @Query(value = """
            UPDATE password_recovery_challenges SET consumed_at = clock_timestamp()
            WHERE id = :id AND issued_credential_version = :version
                AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp()
            """, nativeQuery = true)
    int consumeOpen(@Param("id") UUID id, @Param("version") long version);
}
