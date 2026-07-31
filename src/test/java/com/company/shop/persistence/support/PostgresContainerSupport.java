package com.company.shop.persistence.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base support for persistence integration tests backed by a shared PostgreSQL Testcontainer.
 * <p>
 * Uses PostgreSQL image compatible with {@link PostgreSQLContainer} and injects project
 * Polish FTS dictionary files directly into PostgreSQL tsearch_data before startup.
 * </p>
 */
public abstract class PostgresContainerSupport {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("enterprise_shop_test")
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

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
