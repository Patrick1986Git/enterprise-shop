package com.company.shop.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductionDatabaseOwnershipValidatorTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final ProductionDatabaseOwnershipValidator validator =
            new ProductionDatabaseOwnershipValidator(jdbcTemplate);

    @Test
    void run_shouldPassWhenRuntimeOwnsNoProtectedTables() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void run_shouldFailClearlyWhenRuntimeOwnsProtectedTables() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("notification_admin_action_logs", "outbox_event_admin_action_logs"));

        assertThatIllegalStateException()
                .isThrownBy(() -> validator.run(null))
                .withMessage("Production runtime database identity must not own protected admin action-log tables: "
                        + "notification_admin_action_logs, outbox_event_admin_action_logs");
    }
}
