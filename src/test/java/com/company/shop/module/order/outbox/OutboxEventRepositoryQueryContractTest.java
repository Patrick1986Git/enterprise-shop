package com.company.shop.module.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class OutboxEventRepositoryQueryContractTest {

    @Test
    void dueQueries_shouldDeclareInclusivePostgresTransactionTimePredicates() {
        assertDueContract(queryValue("findDuePendingCandidateIds"));
        assertDueContract(queryValue("findDuePendingByIdForUpdateSkipLocked"));
    }

    private String queryValue(String methodName) {
        Method method = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(Query.class).value().replaceAll("\\s+", " ");
    }

    private void assertDueContract(String query) {
        assertThat(query)
                .contains("next_attempt_at <= CURRENT_TIMESTAMP")
                .doesNotContain("next_attempt_at < CURRENT_TIMESTAMP")
                .doesNotContainIgnoringCase("statement_timestamp()")
                .doesNotContainIgnoringCase("clock_timestamp()");
    }
}
