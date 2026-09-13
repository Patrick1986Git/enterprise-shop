package com.company.shop.module.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.category.api.internal.ProductCategoryFacade;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.exception.CategoryNotFoundException;
import com.company.shop.module.category.exception.CategoryRetirementConflictException;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest
@ActiveProfiles("test")
class CategoryRetirementLifecycleIT extends PostgresContainerSupport {

    @Autowired CategoryService categoryService;
    @Autowired ProductCategoryFacade productCategoryFacade;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void retirement_shouldHideUnreferencedLeafAndRejectRepeatedRetirement() {
        UUID categoryId = persistCategory(null).getId();

        categoryService.delete(categoryId);

        assertThat(categoryRepository.findById(categoryId)).isEmpty();
        assertThat(physicalCategory(categoryId)).isEqualTo(new PhysicalCategory(true, true, null));
        assertThatThrownBy(() -> categoryService.delete(categoryId)).isInstanceOf(CategoryNotFoundException.class);
        assertThat(physicalCategory(categoryId)).isEqualTo(new PhysicalCategory(true, true, null));
    }

    @Test
    void retirement_shouldRejectActiveProductAndPreserveReadableCatalogState() {
        Category category = persistCategory(null);
        Product product = persistProduct(category);

        assertThatThrownBy(() -> categoryService.delete(category.getId()))
                .isInstanceOfSatisfying(CategoryRetirementConflictException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_RETIREMENT_BLOCKED"));

        assertThat(physicalCategory(category.getId()).deleted()).isFalse();
        transaction().executeWithoutResult(status ->
                assertThat(productRepository.findById(product.getId())).hasValueSatisfying(active -> {
                    assertThat(active.getCategory().getId()).isEqualTo(category.getId());
                    assertThat(active.getCategory().getName()).isEqualTo(category.getName());
                }));
    }

    @Test
    void retirement_shouldRejectActiveChildWithoutPartialHierarchyMutation() {
        Category parent = persistCategory(null);
        Category child = persistCategory(parent);

        assertThatThrownBy(() -> categoryService.delete(parent.getId()))
                .isInstanceOf(CategoryRetirementConflictException.class);

        assertThat(physicalCategory(parent.getId()).deleted()).isFalse();
        assertThat(physicalCategory(child.getId()).parentId()).isEqualTo(parent.getId());
        assertThat(categoryRepository.findById(child.getId())).hasValueSatisfying(activeChild ->
                assertThat(activeChild.getParent().getId()).isEqualTo(parent.getId()));
    }

    @Test
    void productAssignmentWinningRace_shouldCommitProductAndRejectRetirement() throws Exception {
        Category category = persistCategory(null);
        CountDownLatch assignmentLocked = new CountDownLatch(1);
        CountDownLatch allowAssignmentCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var assignment = executor.submit(() -> transaction().executeWithoutResult(status -> {
                Category locked = productCategoryFacade.findAssignableCategory(category.getId()).orElseThrow();
                assignmentLocked.countDown();
                await(allowAssignmentCommit);
                productRepository.saveAndFlush(new Product(unique("Race product"), unique("race-product"),
                        unique("RACE"), "description", new BigDecimal("10.00"), 4, locked));
            }));

            assertThat(assignmentLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var retirement = executor.submit(() -> categoryService.delete(category.getId()));
            allowAssignmentCommit.countDown();

            assignment.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> retirement.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(CategoryRetirementConflictException.class);
        }

        assertThat(physicalCategory(category.getId()).deleted()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from products where category_id = ? and deleted = false", Long.class,
                category.getId())).isEqualTo(1L);
    }

    @Test
    void productReassignmentWinningRace_shouldPreserveCatalogChangeAndRejectTargetRetirement() throws Exception {
        Category original = persistCategory(null);
        Category target = persistCategory(null);
        Product product = persistProduct(original);
        CountDownLatch targetLocked = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var reassignment = executor.submit(() -> transaction().executeWithoutResult(status -> {
                Product lockedProduct = productRepository.findByIdWithLock(product.getId()).orElseThrow();
                Category lockedTarget = productCategoryFacade.findAssignableCategory(target.getId()).orElseThrow();
                targetLocked.countDown();
                await(allowCommit);
                lockedProduct.updateCatalog("Reassigned", unique("reassigned"), lockedProduct.getSku(),
                        lockedProduct.getDescription(), lockedProduct.getPrice(), lockedTarget);
                productRepository.flush();
            }));

            assertThat(targetLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var retirement = executor.submit(() -> categoryService.delete(target.getId()));
            allowCommit.countDown();

            reassignment.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> retirement.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(CategoryRetirementConflictException.class);
        }

        assertThat(physicalCategory(target.getId()).deleted()).isFalse();
        assertThat(jdbcTemplate.queryForObject("select category_id from products where id = ?", UUID.class,
                product.getId())).isEqualTo(target.getId());
        assertThat(jdbcTemplate.queryForObject("select name from products where id = ?", String.class,
                product.getId())).isEqualTo("Reassigned");
    }

    @Test
    void parentAssignmentWinningRace_shouldPreserveHierarchyAndRejectParentRetirement() throws Exception {
        Category child = persistCategory(null);
        Category parent = persistCategory(null);
        CountDownLatch parentLocked = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var reassignment = executor.submit(() -> transaction().executeWithoutResult(status -> {
                categoryRepository.acquireHierarchyMutationLock();
                Category lockedChild = categoryRepository.findByIdWithLock(child.getId()).orElseThrow();
                Category lockedParent = categoryRepository.findByIdWithLock(parent.getId()).orElseThrow();
                parentLocked.countDown();
                await(allowCommit);
                lockedChild.update(lockedChild.getName(), lockedChild.getSlug(),
                        lockedChild.getDescription(), lockedParent);
                categoryRepository.flush();
            }));

            assertThat(parentLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var retirement = executor.submit(() -> categoryService.delete(parent.getId()));
            allowCommit.countDown();

            reassignment.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> retirement.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(CategoryRetirementConflictException.class);
        }

        assertThat(physicalCategory(parent.getId()).deleted()).isFalse();
        assertThat(physicalCategory(child.getId()).parentId()).isEqualTo(parent.getId());
    }

    @Test
    void retirementRollback_shouldReleaseLocksAndPreserveActiveState() {
        UUID categoryId = persistCategory(null).getId();

        transaction().executeWithoutResult(status -> {
            categoryService.delete(categoryId);
            status.setRollbackOnly();
        });

        assertThat(categoryRepository.findById(categoryId)).isPresent();
        assertThat(physicalCategory(categoryId).deleted()).isFalse();
    }

    private Category persistCategory(Category parent) {
        return transaction().execute(status -> categoryRepository.saveAndFlush(new Category(
                unique("Category"), unique("category"), "description", parent)));
    }

    private Product persistProduct(Category category) {
        return transaction().execute(status -> productRepository.saveAndFlush(new Product(
                unique("Product"), unique("product"), unique("SKU"), "description",
                new BigDecimal("10.00"), 5, category)));
    }

    private PhysicalCategory physicalCategory(UUID id) {
        return jdbcTemplate.queryForObject(
                "select deleted, deleted_at is not null, parent_id from categories where id = ?",
                (rs, row) -> new PhysicalCategory(rs.getBoolean(1), rs.getBoolean(2),
                        rs.getObject(3, UUID.class)), id);
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for deterministic concurrency barrier");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for deterministic concurrency barrier", ex);
        }
    }

    private record PhysicalCategory(boolean deleted, boolean deletedAtPresent, UUID parentId) { }
}
