package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ReservationExpirationWorkRepositoryQueryContractTest {

    @Test
    void eligibilityQueries_shouldDeclareInclusivePostgresStatementTimePredicates() {
        assertEligibilityContract(queryValue("findDueCandidateIds"));
        assertEligibilityContract(queryValue("findClaimableForUpdate"));
    }

    private String queryValue(String methodName) {
        Method method = Arrays.stream(ReservationExpirationWorkRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(Query.class).value().replaceAll("\\s+", " ");
    }

    private void assertEligibilityContract(String query) {
        assertThat(query)
                .contains("next_attempt_at <= statement_timestamp()")
                .contains("claim_until <= statement_timestamp()")
                .doesNotContain("next_attempt_at < statement_timestamp()")
                .doesNotContain("claim_until < statement_timestamp()");
    }
}
