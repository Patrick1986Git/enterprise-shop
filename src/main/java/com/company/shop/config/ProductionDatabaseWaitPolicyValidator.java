package com.company.shop.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionDatabaseWaitPolicyValidator implements ApplicationRunner {

    private static final String POSITIVE_SETTING_QUERY = "SELECT current_setting(?)::interval > interval '0'";

    private final JdbcTemplate jdbcTemplate;

    public ProductionDatabaseWaitPolicyValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireFinite("statement_timeout");
        requireFinite("lock_timeout");
        requireFinite("idle_in_transaction_session_timeout");

        if (!readBoolean("SELECT current_setting('lock_timeout')::interval "
                + "< current_setting('statement_timeout')::interval", "lock_timeout")) {
            throw new IllegalStateException(
                    "Production runtime database lock_timeout must be shorter than statement_timeout");
        }
    }

    private void requireFinite(String setting) {
        if (!readBoolean(POSITIVE_SETTING_QUERY, setting, setting)) {
            throw new IllegalStateException(
                    "Production runtime database " + setting + " must be enabled with a finite deployment-owned value");
        }
    }

    private boolean readBoolean(String sql, String unsafeSetting, Object... arguments) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, arguments));
        } catch (DataAccessException exception) {
            throw new IllegalStateException(
                    "Production runtime database " + unsafeSetting + " effective value could not be validated");
        }
    }
}
