package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductionDatabaseWaitPolicyValidatorTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final ProductionDatabaseWaitPolicyValidator validator =
            new ProductionDatabaseWaitPolicyValidator(jdbcTemplate);

    @Test
    void run_shouldPassWhenEffectiveRuntimePolicyIsFiniteAndLockTimeoutIsShorter() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void run_shouldRejectEachDisabledRequiredSetting() {
        for (String setting : new String[] {
                "statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout"
        }) {
            when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                    .thenAnswer(invocation -> !setting.equals(invocation.getArgument(2)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> validator.run(null))
                    .withMessage("Production runtime database " + setting
                            + " must be enabled with a finite deployment-owned value");
        }
    }

    @Test
    void run_shouldRejectLockTimeoutThatDoesNotPrecedeStatementTimeout() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArguments().length > 2);

        assertThatIllegalStateException()
                .isThrownBy(() -> validator.run(null))
                .withMessage("Production runtime database lock_timeout must be shorter than statement_timeout");
    }

    @Test
    void run_shouldSanitizeDatabaseReadFailure() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException(
                        "jdbc:postgresql://secret-host/shop user=secret SQL=SELECT current_setting"));

        assertThatIllegalStateException()
                .isThrownBy(() -> validator.run(null))
                .withMessage("Production runtime database statement_timeout effective value could not be validated")
                .withNoCause();
    }
}
