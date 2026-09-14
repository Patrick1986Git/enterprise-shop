package com.company.shop.module.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.company.shop.module.user.entity.User;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text), 0))", nativeQuery = true)
    void acquireCommerceLifecycleLock(@Param("userId") UUID userId);

    @Query("""
            SELECT DISTINCT u
            FROM User u
            JOIN FETCH u.roles
            WHERE lower(u.email) = lower(:email)
              AND u.deleted = false
            """)
    Optional<User> findActiveByEmailWithRoles(@Param("email") String email);

    @Query("SELECT u FROM User u WHERE u.deleted = false")
    @EntityGraph(attributePaths = "roles")
    Page<User> findAllActive(Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deleted = false")
    @EntityGraph(attributePaths = "roles")
    Optional<User> findActiveWithRolesById(@Param("id") UUID id);

    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deleted = false")
    Optional<User> findActiveById(@Param("id") UUID id);
}
