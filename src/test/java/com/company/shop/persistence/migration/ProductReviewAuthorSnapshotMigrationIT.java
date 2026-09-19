package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class ProductReviewAuthorSnapshotMigrationIT {

    @Test
    void migrate_shouldBackfillStableAuthorNameBeforeEnforcingConstraints() {
        try (PostgreSQLContainer<?> postgres = postgres()) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target(MigrationVersion.fromVersion("48"))
                    .load()
                    .migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

            UUID namedReview = insertReview(jdbc, "Alex", "Morgan");
            UUID anonymousReview = insertReview(jdbc, null, null);

            Flyway upgraded = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target(MigrationVersion.fromVersion("49"))
                    .load();
            assertThat(upgraded.migrate().migrationsExecuted).isOne();

            assertThat(jdbc.queryForObject(
                    "select author_name from product_reviews where id = ?", String.class, namedReview))
                    .isEqualTo("Alex Morgan");
            assertThat(jdbc.queryForObject(
                    "select author_name from product_reviews where id = ?", String.class, anonymousReview))
                    .isEqualTo("Anonymous");
            assertThatThrownBy(() -> jdbc.update(
                    "update product_reviews set author_name = null where id = ?", namedReview))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasRootCauseInstanceOf(PSQLException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "update product_reviews set author_name = '  ' where id = ?", namedReview))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasRootCauseInstanceOf(PSQLException.class);
        }
    }

    private UUID insertReview(JdbcTemplate jdbc, String firstName, String lastName) {
        String suffix = UUID.randomUUID().toString();
        UUID userId = jdbc.queryForObject("""
                insert into users (email, password, first_name, last_name)
                values (?, 'encoded', ?, ?) returning id
                """, UUID.class, suffix + "@example.com", firstName, lastName);
        UUID categoryId = jdbc.queryForObject(
                "insert into categories (name, slug) values (?, ?) returning id",
                UUID.class, "Category " + suffix, "category-" + suffix);
        UUID productId = jdbc.queryForObject("""
                insert into products (sku, slug, name, price, stock, category_id, version)
                values (?, ?, 'Migration product', 10.00, 1, ?, 0) returning id
                """, UUID.class, "SKU-" + suffix, "product-" + suffix, categoryId);
        return jdbc.queryForObject("""
                insert into product_reviews (product_id, user_id, rating, version)
                values (?, ?, 5, 0) returning id
                """, UUID.class, productId, userId);
    }

    private PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("product_review_migration")
                .withUsername("shop_test")
                .withPassword("shop_test")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/postgres/tsearch_data/polish.dict"),
                        "/usr/local/share/postgresql/tsearch_data/polish.dict")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/postgres/tsearch_data/polish.affix"),
                        "/usr/local/share/postgresql/tsearch_data/polish.affix")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/postgres/tsearch_data/polish.stop"),
                        "/usr/local/share/postgresql/tsearch_data/polish.stop");
    }
}
