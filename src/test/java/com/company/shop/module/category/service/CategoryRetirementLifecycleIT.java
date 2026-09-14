package com.company.shop.module.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.category.api.internal.ProductCategoryFacade;
import com.company.shop.module.category.dto.CategoryCreateDTO;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.exception.CategoryNotFoundException;
import com.company.shop.module.category.exception.CategoryRetirementConflictException;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.product.dto.ProductCreateDTO;
import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.dto.ProductUpdateDTO;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductCategoryNotFoundException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest
@ActiveProfiles("test")
class CategoryRetirementLifecycleIT extends PostgresContainerSupport {

    @Autowired CategoryService categoryService;
    @MockitoSpyBean ProductCategoryFacade productCategoryFacade;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductService productService;
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
    void retirementWinningRace_shouldRejectProductCreationWithoutPartialPersistence() throws Exception {
        Category category = persistCategory(null);
        CountDownLatch retirementLocked = new CountDownLatch(1);
        CountDownLatch assignmentAttempted = new CountDownLatch(1);
        CountDownLatch allowRetirementCommit = new CountDownLatch(1);
        String sku = unique("RETIRE-FIRST-CREATE");

        doAnswer(invocation -> {
            assignmentAttempted.countDown();
            return invocation.callRealMethod();
        }).when(productCategoryFacade).findAssignableCategory(category.getId());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var retirement = executor.submit(() -> retireHoldingLocks(
                    category.getId(), retirementLocked, allowRetirementCommit));
            assertThat(retirementLocked.await(10, TimeUnit.SECONDS)).isTrue();

            var creation = executor.submit(() -> productService.create(new ProductCreateDTO(
                    "Retirement-first product", sku, "must roll back", new BigDecimal("15.00"),
                    7, category.getId(), List.of("https://example.test/retirement-first.jpg"))));
            assertThat(assignmentAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(creation).isNotDone();

            allowRetirementCommit.countDown();
            retirement.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> creation.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ProductCategoryNotFoundException.class);
        }

        assertRetiredAndHidden(category.getId());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from products where category_id = ?", Long.class, category.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from products where sku = ?", Long.class, sku)).isZero();
    }

    @Test
    void retirementWinningRace_shouldRollbackProductReassignmentAndVersionIncrement() throws Exception {
        Category original = persistCategory(null);
        Category target = persistCategory(null);
        Product product = persistProduct(original);
        PhysicalProduct before = physicalProduct(product.getId());
        CountDownLatch retirementLocked = new CountDownLatch(1);
        CountDownLatch reassignmentAttempted = new CountDownLatch(1);
        CountDownLatch allowRetirementCommit = new CountDownLatch(1);

        doAnswer(invocation -> {
            reassignmentAttempted.countDown();
            return invocation.callRealMethod();
        }).when(productCategoryFacade).findAssignableCategory(target.getId());

        ProductUpdateDTO update = new ProductUpdateDTO(before.version(), "Must not persist",
                unique("must-not-persist"), "must not persist", new BigDecimal("99.99"),
                target.getId(), List.of("https://example.test/must-not-persist.jpg"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var retirement = executor.submit(() -> retireHoldingLocks(
                    target.getId(), retirementLocked, allowRetirementCommit));
            assertThat(retirementLocked.await(10, TimeUnit.SECONDS)).isTrue();

            var reassignment = executor.submit(() -> productService.update(product.getId(), update));
            assertThat(reassignmentAttempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reassignment).isNotDone();

            allowRetirementCommit.countDown();
            retirement.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> reassignment.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ProductCategoryNotFoundException.class);
        }

        assertRetiredAndHidden(target.getId());
        assertThat(physicalProduct(product.getId())).isEqualTo(before);
        ProductResponseDTO readable = productService.findById(product.getId());
        assertThat(readable.getCategoryId()).isEqualTo(original.getId());
        assertThat(readable.getName()).isEqualTo(before.name());
        assertThat(readable.getStock()).isEqualTo(before.stock());
        assertThat(readable.getVersion()).isEqualTo(before.version());
    }

    @Test
    void parentRetirementWinningRace_shouldRollbackEntireChildMutation() throws Exception {
        Category previousParent = persistCategory(null);
        Category child = persistCategory(previousParent);
        Category candidateParent = persistCategory(null);
        PhysicalCategory childBefore = physicalCategory(child.getId());
        String childNameBefore = child.getName();
        String childSlugBefore = child.getSlug();
        String childDescriptionBefore = child.getDescription();
        CountDownLatch retirementLocked = new CountDownLatch(1);
        CountDownLatch childTransactionStarted = new CountDownLatch(1);
        CountDownLatch allowRetirementCommit = new CountDownLatch(1);
        AtomicInteger childBackendPid = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var retirement = executor.submit(() -> retireHoldingLocks(
                    candidateParent.getId(), retirementLocked, allowRetirementCommit));
            assertThat(retirementLocked.await(10, TimeUnit.SECONDS)).isTrue();

            CategoryCreateDTO update = new CategoryCreateDTO(
                    "Must not replace child", "must not replace description", candidateParent.getId());
            var reassignment = executor.submit(() -> transaction().execute(status -> {
                childBackendPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                childTransactionStarted.countDown();
                return categoryService.update(child.getId(), update);
            }));
            assertThat(childTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitHierarchyAdvisoryLockWait(childBackendPid.get())).isTrue();
            assertThat(reassignment).isNotDone();

            allowRetirementCommit.countDown();
            retirement.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> reassignment.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(CategoryNotFoundException.class);
        }

        assertRetiredAndHidden(candidateParent.getId());
        assertThat(physicalCategory(child.getId())).isEqualTo(childBefore);
        transaction().executeWithoutResult(status ->
                assertThat(categoryRepository.findById(child.getId())).hasValueSatisfying(activeChild -> {
                    assertThat(activeChild.getName()).isEqualTo(childNameBefore);
                    assertThat(activeChild.getSlug()).isEqualTo(childSlugBefore);
                    assertThat(activeChild.getDescription()).isEqualTo(childDescriptionBefore);
                    assertThat(activeChild.getParent().getId()).isEqualTo(previousParent.getId());
                }));
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

    private PhysicalProduct physicalProduct(UUID id) {
        return jdbcTemplate.queryForObject("""
                select name, slug, sku, description, price, stock, version, category_id, deleted
                from products where id = ?
                """, (rs, row) -> new PhysicalProduct(rs.getString("name"), rs.getString("slug"),
                rs.getString("sku"), rs.getString("description"), rs.getBigDecimal("price"),
                rs.getInt("stock"), rs.getLong("version"), rs.getObject("category_id", UUID.class),
                rs.getBoolean("deleted")), id);
    }

    private void retireHoldingLocks(UUID categoryId, CountDownLatch retirementLocked,
            CountDownLatch allowRetirementCommit) {
        transaction().executeWithoutResult(status -> {
            categoryRepository.acquireHierarchyMutationLock();
            Category locked = categoryRepository.findByIdWithLock(categoryId).orElseThrow();
            locked.delete();
            categoryRepository.flush();
            retirementLocked.countDown();
            await(allowRetirementCommit);
        });
    }

    private void assertRetiredAndHidden(UUID categoryId) {
        assertThat(physicalCategory(categoryId)).satisfies(category -> {
            assertThat(category.deleted()).isTrue();
            assertThat(category.deletedAtPresent()).isTrue();
        });
        assertThat(categoryRepository.findById(categoryId)).isEmpty();
    }

    private boolean awaitHierarchyAdvisoryLockWait(int backendPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean waiting = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (
                        select 1
                        from pg_locks locks
                        join pg_stat_activity activity on activity.pid = locks.pid
                        where locks.pid = ?
                          and locks.locktype = 'advisory'
                          and locks.classid::bigint = 0
                          and locks.objid::bigint = 1128354383
                          and locks.objsubid = 1
                          and not locks.granted
                          and activity.wait_event_type = 'Lock'
                          and activity.wait_event = 'advisory'
                    )
                    """, Boolean.class, backendPid));
            if (waiting) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
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

    private record PhysicalProduct(String name, String slug, String sku, String description,
            BigDecimal price, int stock, long version, UUID categoryId, boolean deleted) { }
}
