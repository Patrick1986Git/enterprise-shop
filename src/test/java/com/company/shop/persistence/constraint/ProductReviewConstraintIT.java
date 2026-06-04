package com.company.shop.persistence.constraint;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.entity.ProductReview;
import com.company.shop.module.user.entity.User;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ProductReviewConstraintIT extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persist_shouldThrowWhenSecondReviewForSameProductAndUserExists() {
        User user = PersistenceFixtures.persistUser(entityManager, "anna.nowak@example.com");
        Product product = PersistenceFixtures.persistProduct(
                entityManager,
                "Phone X",
                "phone-x",
                "SKU-PHONE-X",
                BigDecimal.valueOf(199.99),
                10);

        PersistenceFixtures.persistProductReview(entityManager, product, user, 5, "Great");
        entityManager.clear();

        Product managedProduct = entityManager.getEntityManager().getReference(Product.class, product.getId());
        User managedUser = entityManager.getEntityManager().getReference(User.class, user.getId());

        ProductReview duplicateReview = new ProductReview(managedProduct, managedUser, 4, "Still good");
        PersistenceFixtures.setCreatedAt(duplicateReview);

        assertThatThrownBy(() -> {
            entityManager.persist(duplicateReview);
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    @Test
    void persist_shouldThrowWhenRatingOutsideAllowedRange() {
        User user = PersistenceFixtures.persistUser(entityManager, "marta.kowalska@example.com");
        Product product = PersistenceFixtures.persistProduct(
                entityManager,
                "Tablet Z",
                "tablet-z",
                "SKU-TABLET-Z",
                BigDecimal.valueOf(199.99),
                10);

        UUID reviewId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                        INSERT INTO product_reviews (
                            id, product_id, user_id, rating, comment, created_at, deleted, version
                        ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, false, 0)
                        """,
                reviewId,
                product.getId(),
                user.getId(),
                6,
                "Invalid rating"
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasRootCauseInstanceOf(PSQLException.class);
    }
}
