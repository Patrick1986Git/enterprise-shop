package com.company.shop.module.category.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.category.api.internal.ProductCategoryFacade;
import com.company.shop.module.category.dto.CategoryCreateDTO;
import com.company.shop.module.category.dto.CategoryResponseDTO;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.exception.CategoryAlreadyExistsException;
import com.company.shop.module.category.exception.CategoryHierarchyException;
import com.company.shop.module.category.exception.CategoryNotFoundException;
import com.company.shop.module.category.exception.CategorySlugAlreadyExistsException;
import com.company.shop.module.category.mapper.CategoryMapper;
import com.company.shop.module.category.repository.CategoryRepository;

/**
 * Production implementation of {@link CategoryService}.
 * <p>
 * Handles the business logic for category management, including slug generation, 
 * recursive hierarchy validation (cycle detection), and transactional persistence.
 * </p>
 *
 * @since 1.0.0
 */
@Service
@Transactional
public class CategoryServiceImpl implements CategoryService, ProductCategoryFacade {

    private final CategoryRepository repo;
    private final CategoryMapper mapper;

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    /**
     * Initialized with repository and mapper for full lifecycle management.
     */
    public CategoryServiceImpl(CategoryRepository repo, CategoryMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CategoryResponseDTO> findAll(Pageable pageable) {
        return repo.findAll(pageable).map(mapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponseDTO findById(UUID id) {
        return repo.findById(id).map(mapper::toDto).orElseThrow(() -> new CategoryNotFoundException(id));
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponseDTO findBySlug(String slug) {
        return repo.findBySlug(slug).map(mapper::toDto).orElseThrow(() -> new CategoryNotFoundException(slug));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Category> findAssignableCategory(UUID categoryId) {
        return repo.findById(categoryId);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Implementation performs pre-persistence validation of name/slug uniqueness 
     * and parent existence.
     * </p>
     */
    @Override
    public CategoryResponseDTO create(CategoryCreateDTO dto) {
        if (repo.existsByName(dto.getName())) {
            throw new CategoryAlreadyExistsException(dto.getName());
        }

        String slug = generateSlug(dto.getName());
        if (repo.existsBySlug(slug)) {
            throw new CategorySlugAlreadyExistsException(slug);
        }

        Category parent = resolveParent(dto.getParentId(), null);
        Category category = new Category(dto.getName(), slug, dto.getDescription(), parent);

        return saveAndMap(category, dto.getName(), slug);
    }

    @Override
    public CategoryResponseDTO update(UUID id, CategoryCreateDTO dto) {
        Category category = repo.findById(id).orElseThrow(() -> new CategoryNotFoundException(id));

        if (repo.existsByNameAndIdNot(dto.getName(), id)) {
            throw new CategoryAlreadyExistsException(dto.getName());
        }

        String newSlug = generateSlug(dto.getName());
        if (repo.existsBySlugAndIdNot(newSlug, id)) {
            throw new CategorySlugAlreadyExistsException(newSlug);
        }

        Category parent = resolveParent(dto.getParentId(), id);
        category.update(dto.getName(), newSlug, dto.getDescription(), parent);

        return saveAndMap(category, dto.getName(), newSlug);
    }

    @Override
    public void delete(UUID id) {
        Category category = repo.findById(id).orElseThrow(() -> new CategoryNotFoundException(id));
        category.delete();
    }

    /**
     * Validates and returns the parent category while preventing self-parenting and cycles.
     */
    private Category resolveParent(UUID parentId, UUID currentCategoryId) {
        if (parentId == null) {
            return null;
        }

        if (currentCategoryId != null && parentId.equals(currentCategoryId)) {
            throw new CategoryHierarchyException("Category cannot be its own parent", "CATEGORY_SELF_PARENT");
        }

        Category parent = repo.findById(parentId).orElseThrow(() -> new CategoryNotFoundException(parentId));

        if (currentCategoryId != null && createsCycle(currentCategoryId, parent)) {
            throw new CategoryHierarchyException("Category hierarchy cycle detected", "CATEGORY_CYCLE_DETECTED");
        }

        return parent;
    }

    /**
     * Recursively checks if assigning a parent would create a circular dependency.
     */
    private boolean createsCycle(UUID currentCategoryId, Category candidateParent) {
        Category cursor = candidateParent;
        while (cursor != null) {
            if (currentCategoryId.equals(cursor.getId())) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    /**
     * Persists the category and handles potential race conditions via catch blocks.
     */
    private CategoryResponseDTO saveAndMap(Category category, String categoryName, String slug) {
        try {
            return mapper.toDto(repo.saveAndFlush(category));
        } catch (DataIntegrityViolationException ex) {
            String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : "";
            if (message != null && message.toLowerCase(Locale.ENGLISH).contains("slug")) {
                throw new CategorySlugAlreadyExistsException(slug);
            }
            throw new CategoryAlreadyExistsException(categoryName);
        }
    }

    /**
     * Normalizes input strings into URL-friendly slugs.
     */
    private String generateSlug(String input) {
        String nowhitespace = WHITESPACE.matcher(input).replaceAll("-");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        slug = slug.replaceAll("-{2,}", "-");
        slug = slug.replaceAll("^-|-$", "");
        return slug.toLowerCase(Locale.ENGLISH);
    }
}
