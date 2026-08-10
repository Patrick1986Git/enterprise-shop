package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.dto.ProductReviewRequestDTO;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.module.user.service.UserService;
import com.company.shop.persistence.support.PersistenceFixtures;
import com.company.shop.persistence.support.PostgresContainerSupport;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(ProductReviewAggregateConcurrencyIT.LockCoordinationConfiguration.class)
class ProductReviewAggregateConcurrencyIT extends PostgresContainerSupport {

    private static final long TIMEOUT_SECONDS = 10L;

    @Autowired
    private ProductReviewService productReviewService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductLockCoordinator productLockCoordinator;

    @MockitoBean
    private UserService userService;

    @Test
    void addReview_shouldKeepProductAggregateConsistentWhenDifferentUsersWriteConcurrently() throws Exception {
        Fixture fixture = persistFixture();
        ThreadLocal<UUID> currentUserId = new ThreadLocal<>();
        when(userService.getCurrentUserEntity()).thenAnswer(invocation -> userRepository.findById(currentUserId.get())
                .orElseThrow());

        productLockCoordinator.coordinateNextTwoAttempts();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Attempt firstAttempt;
        Attempt secondAttempt;
        try {
            Future<Attempt> firstReview = executor.submit(() -> addReview(
                    currentUserId, fixture.firstUserId(), new ProductReviewRequestDTO(fixture.productId(), 5, "Excellent")));
            Future<Attempt> secondReview = executor.submit(() -> addReview(
                    currentUserId, fixture.secondUserId(), new ProductReviewRequestDTO(fixture.productId(), 3, "Acceptable")));

            firstAttempt = firstReview.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondAttempt = secondReview.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("concurrent review executor should terminate within %s seconds", TIMEOUT_SECONDS)
                    .isTrue();
        }

        PersistedState state = loadPersistedState(fixture.productId());
        String diagnostic = diagnostic(firstAttempt, secondAttempt, state);

        assertThat(firstAttempt.failure()).as(diagnostic).isNull();
        assertThat(secondAttempt.failure()).as(diagnostic).isNull();
        assertThat(state.activeReviewCount()).as(diagnostic).isEqualTo(2L);
        assertThat(state.reviewUserIds()).as(diagnostic)
                .containsExactlyInAnyOrder(fixture.firstUserId(), fixture.secondUserId());
        assertThat(state.storedReviewCount()).as(diagnostic).isEqualTo(Math.toIntExact(state.authoritativeCount()));
        assertThat(state.storedAverageRating()).as(diagnostic).isEqualTo(state.authoritativeAverage());
    }

    private Fixture persistFixture() {
        return transactionTemplate.execute(status -> {
            Category category = new Category("Concurrency category", "concurrency-" + UUID.randomUUID(), "test");
            User firstUser = new User(uniqueEmail("first"), "encoded-pass", "First", "Reviewer");
            User secondUser = new User(uniqueEmail("second"), "encoded-pass", "Second", "Reviewer");
            Product product = new Product(
                    "Concurrent reviews product",
                    "concurrent-reviews-" + UUID.randomUUID(),
                    "CONCURRENT-" + UUID.randomUUID(),
                    "test",
                    BigDecimal.TEN,
                    10,
                    category);
            List.of(category, firstUser, secondUser, product).forEach(entity -> {
                PersistenceFixtures.setCreatedAt(entity);
                entityManager.persist(entity);
            });
            entityManager.flush();
            return new Fixture(product.getId(), firstUser.getId(), secondUser.getId());
        });
    }

    private Attempt addReview(ThreadLocal<UUID> currentUserId, UUID userId, ProductReviewRequestDTO request) {
        currentUserId.set(userId);
        try {
            return new Attempt(productReviewService.addReview(request).id(), null);
        } catch (Throwable failure) {
            return new Attempt(null, failure);
        } finally {
            currentUserId.remove();
        }
    }

    private PersistedState loadPersistedState(UUID productId) {
        return transactionTemplate.execute(status -> {
            entityManager.clear();
            List<UUID> reviewUserIds = jdbcTemplate.queryForList(
                    "SELECT user_id FROM product_reviews WHERE product_id = ? AND deleted = false ORDER BY user_id",
                    UUID.class,
                    productId);
            return jdbcTemplate.queryForObject(
                    """
                            SELECT p.average_rating, p.review_count, p.version,
                                   COALESCE(AVG(r.rating), 0), COUNT(r.id)
                            FROM products p
                            LEFT JOIN product_reviews r ON r.product_id = p.id AND r.deleted = false
                            WHERE p.id = ?
                            GROUP BY p.id
                            """,
                    (resultSet, rowNumber) -> new PersistedState(
                            reviewUserIds,
                            resultSet.getLong(5),
                            resultSet.getDouble(4),
                            resultSet.getInt(2),
                            resultSet.getDouble(1),
                            resultSet.getLong(3)),
                    productId);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LockCoordinationConfiguration {

        @Bean
        ProductLockCoordinator productLockCoordinator() {
            return new ProductLockCoordinator();
        }

        @Bean
        static BeanPostProcessor productRepositoryLockCoordinator(ProductLockCoordinator coordinator) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ProductRepository)) {
                        return bean;
                    }
                    ProxyFactory proxyFactory = new ProxyFactory(bean);
                    proxyFactory.setInterfaces(ProductRepository.class);
                    proxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        if (invocation.getMethod().getName().equals("findByIdWithLock")) {
                            coordinator.beforeLockAttempt();
                        }
                        return invocation.proceed();
                    });
                    return proxyFactory.getProxy();
                }
            };
        }
    }

    static class ProductLockCoordinator {

        private CyclicBarrier barrier;
        private int remainingArrivals;

        synchronized void coordinateNextTwoAttempts() {
            barrier = new CyclicBarrier(2);
            remainingArrivals = 2;
        }

        void beforeLockAttempt() {
            CyclicBarrier currentBarrier;
            synchronized (this) {
                if (remainingArrivals == 0) {
                    return;
                }
                currentBarrier = barrier;
                remainingArrivals--;
                if (remainingArrivals == 0) {
                    barrier = null;
                }
            }
            awaitBarrier(currentBarrier);
        }

        private void awaitBarrier(CyclicBarrier currentBarrier) {
            try {
                currentBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while coordinating concurrent product lock attempts", ex);
            } catch (BrokenBarrierException | TimeoutException ex) {
                throw new AssertionError("both review transactions did not attempt the product lock within "
                        + TIMEOUT_SECONDS + " seconds", ex);
            }
        }
    }

    private String diagnostic(Attempt firstAttempt, Attempt secondAttempt, PersistedState state) {
        return "concurrent review diagnostic: first=" + describe(firstAttempt)
                + ", second=" + describe(secondAttempt)
                + ", activeReviewCount=" + state.activeReviewCount()
                + ", reviewUserIds=" + state.reviewUserIds()
                + ", authoritativeAverage=" + state.authoritativeAverage()
                + ", authoritativeCount=" + state.authoritativeCount()
                + ", storedAverageRating=" + state.storedAverageRating()
                + ", storedReviewCount=" + state.storedReviewCount()
                + ", productVersion=" + state.productVersion();
    }

    private String describe(Attempt attempt) {
        if (attempt.failure() == null) {
            return "committed(reviewId=" + attempt.reviewId() + ")";
        }
        StringBuilder causes = new StringBuilder();
        Throwable cause = attempt.failure();
        while (cause != null) {
            if (!causes.isEmpty()) {
                causes.append(" -> ");
            }
            causes.append(cause.getClass().getName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return "failed(" + causes + ")";
    }

    private String uniqueEmail(String prefix) {
        return prefix + ".concurrency." + UUID.randomUUID() + "@example.com";
    }

    private record Fixture(UUID productId, UUID firstUserId, UUID secondUserId) {
    }

    private record Attempt(UUID reviewId, Throwable failure) {
    }

    private record PersistedState(
            List<UUID> reviewUserIds,
            long activeReviewCount,
            double authoritativeAverage,
            int storedReviewCount,
            double storedAverageRating,
            long productVersion) {
        private long authoritativeCount() {
            return activeReviewCount;
        }
    }
}
