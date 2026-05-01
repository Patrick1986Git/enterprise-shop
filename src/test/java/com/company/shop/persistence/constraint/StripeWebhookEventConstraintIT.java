package com.company.shop.persistence.constraint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.company.shop.module.order.entity.StripeWebhookEvent;
import com.company.shop.persistence.support.PostgresContainerSupport;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class StripeWebhookEventConstraintIT extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persist_shouldThrowWhenStripeEventIdIsDuplicated() {
        StripeWebhookEvent firstEvent = new StripeWebhookEvent("evt_duplicate", "payment_intent.succeeded",
                LocalDateTime.now());
        entityManager.persist(firstEvent);
        entityManager.flush();

        assertThatThrownBy(() -> {
            StripeWebhookEvent secondEvent = new StripeWebhookEvent("evt_duplicate", "payment_intent.succeeded",
                    LocalDateTime.now());
            entityManager.persist(secondEvent);
            entityManager.flush();
        }).satisfies(ex -> assertThat(hasConstraintName(ex, "uq_stripe_webhook_events_stripe_event_id"))
                .as("Expected cause-chain to contain uq_stripe_webhook_events_stripe_event_id")
                .isTrue());
    }

    private boolean hasConstraintName(Throwable throwable, String expectedConstraintName) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof ConstraintViolationException constraintViolationException) {
                return expectedConstraintName.equals(constraintViolationException.getConstraintName());
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
