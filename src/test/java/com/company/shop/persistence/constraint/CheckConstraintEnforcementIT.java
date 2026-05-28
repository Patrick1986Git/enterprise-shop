package com.company.shop.persistence.constraint;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.persistence.support.PostgresContainerSupport;

@SpringBootTest(
        classes = CheckConstraintEnforcementIT.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class CheckConstraintEnforcementIT extends PostgresContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void insert_shouldFailWhenDiscountCodeUsageLimitIsNotPositive() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO discount_codes(code, discount_percent, valid_from, valid_to, usage_limit, used_count) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                "DISC-" + UUID.randomUUID(),
                10,
                Timestamp.valueOf(LocalDateTime.now().minusDays(1)),
                Timestamp.valueOf(LocalDateTime.now().plusDays(1)),
                0,
                0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldFailWhenDiscountCodeDateRangeIsInvalid() {
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO discount_codes(code, discount_percent, valid_from, valid_to, usage_limit, used_count) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                "DATE-" + UUID.randomUUID(),
                15,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                1,
                0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldFailWhenPaymentMethodIsOutsideApplicationContract() {
        UUID userId = insertUser();
        UUID orderId = insertOrder(userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments(order_id, payment_method, status, amount) VALUES (?, ?, ?, ?)",
                orderId,
                "PAYPAL",
                "PENDING",
                BigDecimal.valueOf(99.99)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldFailWhenPaymentStatusIsOutsideApplicationContract() {
        UUID userId = insertUser();
        UUID orderId = insertOrder(userId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments(order_id, payment_method, status, amount) VALUES (?, ?, ?, ?)",
                orderId,
                "STRIPE",
                "AUTHORIZED",
                BigDecimal.valueOf(99.99)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insert_shouldFailWhenOrderStatusIsOutsideApplicationContract() {
        UUID userId = insertUser();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO orders(user_id, user_email, status, total_amount) VALUES (?, ?, ?, ?)",
                userId,
                "check@example.com",
                "PROCESSING",
                BigDecimal.valueOf(120.00)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(email, password, first_name, last_name) VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class,
                "enforce-" + UUID.randomUUID() + "@example.com",
                "encoded-pass",
                "Check",
                "User");
    }

    private UUID insertOrder(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO orders(user_id, user_email, status, total_amount) VALUES (?, ?, ?, ?) RETURNING id",
                UUID.class,
                userId,
                "check@example.com",
                "NEW",
                BigDecimal.valueOf(120.00));
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
