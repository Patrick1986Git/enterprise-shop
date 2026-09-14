package com.company.shop.module.category.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.company.shop.module.category.entity.Category;

import jakarta.persistence.LockModeType;

/**
 * Repository interface for {@link Category} entity persistence management.
 * <p>
 * Provides specialized query methods for slug-based lookups and existence checks 
 * to support business validation rules.
 * </p>
 *
 * @since 1.0.0
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select category from Category category where category.id = :id")
    Optional<Category> findByIdWithLock(@Param("id") UUID id);

    @Query(value = "select pg_advisory_xact_lock(1128354383)", nativeQuery = true)
    void acquireHierarchyMutationLock();

    boolean existsByParentId(UUID parentId);

    /**
     * Retrieves a category by its URL-friendly slug.
     *
     * @param slug the slug to search for.
     * @return an {@link Optional} containing the found category, or empty if none matches.
     */
    Optional<Category> findBySlug(String slug);

    /**
     * Checks if a category exists with the given name.
     *
     * @param name the name to check.
     * @return true if name exists, false otherwise.
     */
    boolean existsByName(String name);

    /**
     * Checks if a category exists with the given slug.
     *
     * @param slug the slug to check.
     * @return true if slug exists, false otherwise.
     */
    boolean existsBySlug(String slug);

    /**
     * Validates name uniqueness during updates, excluding a specific category ID.
     *
     * @param name the name to check.
     * @param id   the ID of the category being updated.
     * @return true if another category already uses this name.
     */
    boolean existsByNameAndIdNot(String name, UUID id);

    /**
     * Validates slug uniqueness during updates, excluding a specific category ID.
     *
     * @param slug the slug to check.
     * @param id   the ID of the category being updated.
     * @return true if another category already uses this slug.
     */
    boolean existsBySlugAndIdNot(String slug, UUID id);
}
