package com.company.shop.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.entity.ProductImage;
import com.company.shop.module.product.mapper.ProductMapper;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ProductRepositoryIT extends PostgresContainerSupport {

    private final ProductMapper productMapper = org.mapstruct.factory.Mappers.getMapper(ProductMapper.class);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void galleryOrder_shouldSurviveCreateAndReplacementReload() {
        Product product = PersistenceFixtures.persistProduct(entityManager, "Gallery", "gallery", "SKU-GALLERY",
                BigDecimal.TEN, 1);
        product.replaceImages(List.of("A", "B", "C"));
        entityManager.flush();
        UUID productId = product.getId();
        entityManager.clear();

        Product created = productRepository.findById(productId).orElseThrow();
        assertThat(productMapper.toDto(created).getImageUrls()).containsExactly("A", "B", "C");
        assertThat(created.getMainImageUrl()).isEqualTo("A");
        assertThat(created.getImages()).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2);

        created.replaceImages(List.of("C", "A", "B"));
        entityManager.flush();
        entityManager.clear();

        Product reordered = productRepository.findById(productId).orElseThrow();
        assertThat(productMapper.toDto(reordered).getImageUrls()).containsExactly("C", "A", "B");
        assertThat(reordered.getMainImageUrl()).isEqualTo("C");
        assertThat(reordered.getImages()).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2);
    }

    @Test
    void galleryOrder_shouldUseImageIdAsFallbackForEqualLegacyPositions() {
        Product product = PersistenceFixtures.persistProduct(entityManager, "Legacy", "legacy", "SKU-LEGACY",
                BigDecimal.TEN, 1);
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO product_images (id, product_id, image_url, sort_order)
                VALUES (:secondId, :productId, 'B', 7), (:firstId, :productId, 'A', 7)
                """)
                .setParameter("secondId", secondId)
                .setParameter("firstId", firstId)
                .setParameter("productId", product.getId())
                .executeUpdate();
        entityManager.clear();

        Product reloaded = productRepository.findById(product.getId()).orElseThrow();

        assertThat(productMapper.toDto(reloaded).getImageUrls()).containsExactly("A", "B");
        assertThat(reloaded.getMainImageUrl()).isEqualTo("A");
    }

    @Test
    void findByCategoryId_shouldReturnOnlyActiveProductsFromGivenCategory() {
        Category electronics = PersistenceFixtures.persistCategory(entityManager, "electronics");
        Category books = PersistenceFixtures.persistCategory(entityManager, "books");

        Product activePhone = PersistenceFixtures.persistProduct(entityManager, "Phone", "phone", "SKU-PHONE",
                BigDecimal.valueOf(2499L), 20, electronics);
        Product deletedLaptop = PersistenceFixtures.persistProduct(entityManager, "Laptop", "laptop", "SKU-LAPTOP",
                BigDecimal.valueOf(4999L), 10, electronics);
        Product book = PersistenceFixtures.persistProduct(entityManager, "Book", "book", "SKU-BOOK",
                BigDecimal.valueOf(99L), 100, books);

        Product managedDeletedLaptop = entityManager.getEntityManager().find(Product.class, deletedLaptop.getId());
        managedDeletedLaptop.markDeleted();
        entityManager.flush();
        entityManager.clear();

        var page = productRepository.findByCategoryId(
                electronics.getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getContent())
                .hasSize(1)
                .extracting(Product::getId, Product::getName, product -> product.getCategory().getId())
                .containsExactly(tuple(activePhone.getId(), "Phone", electronics.getId()));
        assertThat(page.getTotalElements()).isEqualTo(1L);
        assertThat(page.getTotalPages()).isEqualTo(1);

        assertThat(page.getContent())
                .extracting(Product::isDeleted)
                .containsOnly(false);
        assertThat(page.getContent())
                .extracting(Product::getId)
                .doesNotContain(deletedLaptop.getId(), book.getId());
    }
}
