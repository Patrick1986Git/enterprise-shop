package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.dto.ProductUpdateDTO;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductSkuAlreadyExistsException;
import com.company.shop.module.product.exception.ProductUpdateConflictException;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
class ProductAdminConcurrencyIT extends PostgresContainerSupport {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void galleryOnlyUpdate_shouldReturnSuccessorVersionAndRejectStaleCommand() {
        Snapshot initial = persistProduct("Gallery-only", "SKU-GALLERY", 9, List.of("A", "B", "C"), 4.5, 2);

        ProductResponseDTO updated = productService.update(initial.productId(),
                update(initial, initial.sku(), List.of("C", "A", "B")));

        assertThat(updated.getVersion()).isGreaterThan(initial.version());
        assertThat(updated.getStock()).isEqualTo(9);
        assertThat(updated.getAverageRating()).isEqualTo(4.5);
        assertThat(updated.getReviewCount()).isEqualTo(2);
        assertThat(updated.getImageUrls()).containsExactly("C", "A", "B");

        assertThatThrownBy(() -> productService.update(initial.productId(),
                update(initial, initial.sku(), List.of("stale"))))
                .isInstanceOf(ProductUpdateConflictException.class);

        Snapshot persisted = snapshot(initial.productId());
        assertThat(persisted.version()).isEqualTo(updated.getVersion());
        assertThat(persisted.stock()).isEqualTo(9);
        assertThat(persisted.averageRating()).isEqualTo(4.5);
        assertThat(persisted.reviewCount()).isEqualTo(2);
        assertThat(persisted.images()).containsExactly("C", "A", "B");
        assertThat(persisted.mainImage()).isEqualTo("C");
    }

    @Test
    void concurrentGalleryUpdatesFromSameVersion_shouldAllowExactlyOne() throws Exception {
        Snapshot initial = persistProduct("Concurrent", "SKU-CONCURRENT", 7, List.of("A", "B"), 0.0, 0);
        CountDownLatch firstHasRowLock = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ProductResponseDTO> first = executor.submit(() -> transaction.execute(status -> {
                productRepository.findByIdWithLock(initial.productId()).orElseThrow();
                firstHasRowLock.countDown();
                await(secondStarted);
                return productService.update(initial.productId(), update(initial, initial.sku(), List.of("FIRST")));
            }));
            Future<Object> second = executor.submit(() -> {
                await(firstHasRowLock);
                secondStarted.countDown();
                try {
                    return productService.update(initial.productId(), update(initial, initial.sku(), List.of("SECOND")));
                } catch (ProductUpdateConflictException conflict) {
                    return conflict;
                }
            });

            ProductResponseDTO winner = first.get(10, TimeUnit.SECONDS);
            Object loser = second.get(10, TimeUnit.SECONDS);

            assertThat(winner.getImageUrls()).containsExactly("FIRST");
            assertThat(loser).isInstanceOf(ProductUpdateConflictException.class);
            Snapshot persisted = snapshot(initial.productId());
            assertThat(persisted.images()).containsExactly("FIRST");
            assertThat(persisted.version()).isEqualTo(winner.getVersion()).isGreaterThan(initial.version());
        }
    }

    @Test
    void failedUpdateAfterForceIncrement_shouldRollBackVersionAndAggregate() {
        Snapshot initial = persistProduct("Rollback", "SKU-ROLLBACK", 5, List.of("A", "B"), 3.5, 4);
        Snapshot duplicate = persistProduct("Duplicate", "SKU-DUPLICATE", 1, List.of("D"), 0.0, 0);

        assertThatThrownBy(() -> productService.update(initial.productId(),
                update(initial, duplicate.sku(), List.of("CHANGED"))))
                .isInstanceOf(ProductSkuAlreadyExistsException.class);

        Snapshot persisted = snapshot(initial.productId());
        assertThat(persisted.version()).isEqualTo(initial.version());
        assertThat(persisted.name()).isEqualTo(initial.name());
        assertThat(persisted.stock()).isEqualTo(initial.stock());
        assertThat(persisted.averageRating()).isEqualTo(initial.averageRating());
        assertThat(persisted.reviewCount()).isEqualTo(initial.reviewCount());
        assertThat(persisted.images()).containsExactlyElementsOf(initial.images());
    }

    private Snapshot persistProduct(String name, String sku, int stock, List<String> images,
            double averageRating, int reviewCount) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Category category = new Category("Category " + suffix, "category-" + suffix, "desc");
            entityManager.persist(category);
            Product product = new Product(name, "product-" + suffix, sku + "-" + suffix, "desc",
                    BigDecimal.TEN, stock, category);
            product.updateRatings(averageRating, reviewCount);
            product.replaceImages(images);
            entityManager.persist(product);
            entityManager.flush();
            return toSnapshot(product);
        });
    }

    private Snapshot snapshot(UUID productId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                toSnapshot(productRepository.findById(productId).orElseThrow()));
    }

    private Snapshot toSnapshot(Product product) {
        return new Snapshot(product.getId(), product.getVersion(), product.getName(), product.getSku(),
                product.getDescription(), product.getPrice(), product.getStock(), product.getCategory().getId(),
                product.getAverageRating(), product.getReviewCount(),
                product.getImages().stream().map(image -> image.getImageUrl()).toList(), product.getMainImageUrl());
    }

    private ProductUpdateDTO update(Snapshot snapshot, String sku, List<String> images) {
        return new ProductUpdateDTO(snapshot.version(), snapshot.name(), sku, snapshot.description(), snapshot.price(),
                snapshot.categoryId(), images);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrency barrier");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrency barrier", ex);
        }
    }

    private record Snapshot(UUID productId, long version, String name, String sku, String description,
            BigDecimal price, int stock, UUID categoryId, Double averageRating, int reviewCount,
            List<String> images, String mainImage) {
    }
}
