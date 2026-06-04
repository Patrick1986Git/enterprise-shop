package com.company.shop.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;

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
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ProductRepositoryIT extends PostgresContainerSupport {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager entityManager;

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
