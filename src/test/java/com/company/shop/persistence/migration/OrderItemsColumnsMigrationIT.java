package com.company.shop.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest(
        classes = OrderItemsColumnsMigrationIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class OrderItemsColumnsMigrationIT extends PostgresContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrate_shouldDropLegacyAuditAndSoftDeleteColumnsFromOrderItems() {
        List<String> orderItemsColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = 'order_items'",
                String.class);

        assertThat(orderItemsColumns)
        		.contains(
        				"id",
        				"order_id",
        				"product_id",
        				"quantity",
        				"price",
					"product_name")
                .doesNotContain(
                        "created_at",
                        "created_by",
                        "updated_at",
                        "updated_by",
                        "deleted",
                        "deleted_at");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    static class TestConfig {
    }
}
