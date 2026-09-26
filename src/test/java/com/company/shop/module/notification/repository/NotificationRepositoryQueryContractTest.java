package com.company.shop.module.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class NotificationRepositoryQueryContractTest {

    @Test
    void eligibilityQueries_shouldDeclareInclusivePostgresStatementTimePredicates() {
        assertNextAttemptContract(queryValue("countDuePending"));
        assertEligibilityContract(queryValue("countActionable"));
        assertEligibilityContract(queryValue("findOldestActionableAt"));
        assertEligibilityContract(queryValue("findOldestActionableAgeSeconds"));
        assertEligibilityContract(queryValue("findClaimableBatchForUpdate"));
        assertClaimExpiryContract(queryValue("failExhaustedExpiredClaims"));
    }

    @Test
    void eligibilityQueries_shouldNotAcceptApplicationTimeParameters() {
        Arrays.stream(NotificationRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().matches(
                        "countDuePending|countActionable|findOldestActionableAt|findOldestActionableAgeSeconds|"
                                + "findClaimableBatchForUpdate|failExhaustedExpiredClaims"))
                .forEach(method -> assertThat(method.getParameterTypes()).doesNotContain(Instant.class));
    }

    private String queryValue(String methodName) {
        Method method = Arrays.stream(NotificationRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("@Query on %s", methodName).isNotNull();
        return query.value().replaceAll("\\s+", " ");
    }

    private void assertEligibilityContract(String query) {
        assertNextAttemptContract(query);
        assertClaimExpiryContract(query);
    }

    private void assertNextAttemptContract(String query) {
        assertThat(query)
                .contains("next_attempt_at <= statement_timestamp()")
                .doesNotContain("next_attempt_at < statement_timestamp()")
                .doesNotContainIgnoringCase("current_timestamp");
    }

    private void assertClaimExpiryContract(String query) {
        assertThat(query)
                .contains("claim_expires_at <= statement_timestamp()")
                .doesNotContain("claim_expires_at < statement_timestamp()")
                .doesNotContainIgnoringCase("current_timestamp");
    }
}
