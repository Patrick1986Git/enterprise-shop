package com.company.shop.module.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.module.order.entity.DiscountCode;

import jakarta.persistence.EntityManager;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DiscountCodeLockTimeoutIT extends PostgresContainerSupport {

    private static final String LOCK_NOT_AVAILABLE_SQL_STATE = "55P03";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DiscountCodeRepository discountCodeRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @Timeout(20)
    void findByCodeIgnoreCase_shouldSerializeConsumptionOfFinalUsageSlot() throws Exception {
        String code = "FINAL-" + UUID.randomUUID().toString().substring(0, 8);
        insertDiscountCode(code);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> consumeIfEligible(code, start));
            Future<Boolean> second = executor.submit(() -> consumeIfEligible(code, start));
            start.countDown();

            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        Number usedCount = (Number) entityManager.createNativeQuery(
                "SELECT used_count FROM discount_codes WHERE code = :code")
                .setParameter("code", code)
                .getSingleResult();
        assertThat(usedCount.intValue()).isEqualTo(1);
    }

    @Test
    @Timeout(20)
    void findByCodeIgnoreCase_shouldUseDeploymentOwnedPostgresLockTimeoutUnderContention() throws Exception {
        String code = "LOCK-" + UUID.randomUUID().toString().substring(0, 8);
        insertDiscountCode(code);

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch contenderAttemptedLock = new CountDownLatch(1);
        CountDownLatch contenderFinished = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> lockHolder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                assertThat(discountCodeRepository.findByCodeIgnoreCase(code)).isPresent();
                lockHeld.countDown();
                await(contenderAttemptedLock);
                await(contenderFinished);
            }));

            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> contender = executor.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        entityManager.createNativeQuery("SET LOCAL lock_timeout = '100ms'").executeUpdate();
                        contenderAttemptedLock.countDown();
                        discountCodeRepository.findByCodeIgnoreCase(code);
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                } finally {
                    contenderFinished.countDown();
                }
            });

            Throwable failure = contender.get(10, TimeUnit.SECONDS);
            lockHolder.get(10, TimeUnit.SECONDS);

            assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
            PSQLException postgresFailure = findCause(failure, PSQLException.class);
            assertThat((Throwable) postgresFailure).isNotNull();
            assertThat(postgresFailure.getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE_SQL_STATE);
            assertThat(postgresFailure.getServerErrorMessage().getMessage())
                    .contains("lock timeout");
        }
    }

    private boolean consumeIfEligible(String code, CountDownLatch start) {
        await(start);
        return transactionTemplate.execute(status -> {
            DiscountCode discountCode = discountCodeRepository.findByCodeIgnoreCase(code).orElseThrow();
            boolean eligible = discountCode.canBeUsed(LocalDateTime.of(2026, Month.SEPTEMBER, 26, 12, 0));
            if (eligible) {
                discountCode.incrementUsage();
            }
            return eligible;
        });
    }

    private void insertDiscountCode(String code) {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.of(2026, Month.SEPTEMBER, 26, 12, 0);
            entityManager.createNativeQuery("""
                    INSERT INTO discount_codes (
                        id, code, discount_percent, valid_from, valid_to, usage_limit, used_count, active,
                        created_at, deleted
                    ) VALUES (
                        :id, :code, 10, :validFrom, :validTo, 1, 0, true, :createdAt, false
                    )
                    """)
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("code", code)
                    .setParameter("validFrom", now.minusDays(1))
                    .setParameter("validTo", now.plusDays(1))
                    .setParameter("createdAt", now)
                    .executeUpdate();
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating lock contention", exception);
        }
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
