package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.product.dto.ProductReviewRequestDTO;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.entity.ProductReview;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.product.repository.ProductReviewRepository;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.exception.UserNotFoundException;
import com.company.shop.module.user.repository.RoleRepository;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.module.user.service.UserService;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.security.CurrentUserProvider;
import com.company.shop.security.SecurityConstants;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@AutoConfigureMockMvc
class ProductReviewUserLifecycleIT extends PostgresContainerSupport {

    private static final long TIMEOUT_SECONDS = 10L;

    @Autowired private ProductReviewService productReviewService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductReviewRepository reviewRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;

    @MockitoBean private CurrentUserProvider currentUserProvider;

    private final ThreadLocal<String> authenticatedEmail = new ThreadLocal<>();

    @BeforeEach
    void configureCurrentUser() {
        org.mockito.Mockito.when(currentUserProvider.getCurrentUserEmail())
                .thenAnswer(invocation -> authenticatedEmail.get());
    }

    @AfterEach
    void clearCurrentUser() {
        authenticatedEmail.remove();
    }

    @Test
    void retirement_shouldRetainPublishedReviewSnapshotAndAggregateWithoutMaterializingRetiredUser() throws Exception {
        Fixture fixture = fixture(SecurityConstants.ROLE_USER);
        authenticatedEmail.set(fixture.user().getEmail());
        UUID reviewId = productReviewService.addReview(
                new ProductReviewRequestDTO(fixture.product().getId(), 5, "Durable opinion")).id();

        userService.delete(fixture.user().getId());
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("select deleted from users where id = ?", Boolean.class,
                fixture.user().getId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("select deleted from product_reviews where id = ?", Boolean.class,
                reviewId)).isFalse();
        assertThat(jdbcTemplate.queryForObject("select user_id from product_reviews where id = ?", UUID.class,
                reviewId)).isEqualTo(fixture.user().getId());
        assertThat(jdbcTemplate.queryForObject("select author_name from product_reviews where id = ?", String.class,
                reviewId)).isEqualTo("Review Author");

        ProductReview review = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(review.getAuthorId()).isEqualTo(fixture.user().getId());
        assertThat(review.getAuthorName()).isEqualTo("Review Author");
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            entityManager.clear();
            ProductReview managedReview = reviewRepository.findById(reviewId).orElseThrow();
            managedReview.getUser().getFirstName();
        }))
                .isInstanceOf(EntityNotFoundException.class);

        assertThat(productReviewService.getProductReviews(fixture.product().getId(),
                org.springframework.data.domain.Pageable.unpaged()).getContent())
                .singleElement()
                .satisfies(response -> {
                    assertThat(response.authorName()).isEqualTo("Review Author");
                    assertThat(response.rating()).isEqualTo(5);
                });
        mockMvc.perform(get("/api/v1/products/{productId}/reviews", fixture.product().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authorName").value("Review Author"))
                .andExpect(jsonPath("$.content[0].rating").value(5))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Hibernate"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SQL"))));

        assertAggregate(fixture.product().getId(), 1, 5.0);
    }

    @Test
    void retirementWinning_shouldRejectWaitingReviewCreationWithoutChangingAggregate() throws Exception {
        Fixture fixture = fixture(SecurityConstants.ROLE_USER);
        CountDownLatch retired = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
                userRepository.acquireCommerceLifecycleLock(fixture.user().getId());
                userRepository.findActiveById(fixture.user().getId()).orElseThrow().markDeleted();
                userRepository.flush();
                retired.countDown();
                await(commit);
            }));
            assertThat(retired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> creation = executor.submit(() -> transaction().executeWithoutResult(status -> {
                authenticatedEmail.set(fixture.user().getEmail());
                waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                productReviewService.addReview(new ProductReviewRequestDTO(fixture.product().getId(), 4, "Too late"));
            }));
            assertThat(awaitAdvisoryWait(waiterPid)).isTrue();
            commit.countDown();
            retirement.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThatThrownBy(() -> creation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(UserNotFoundException.class);
        } finally {
            commit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(physicalReviewCount(fixture.product().getId())).isZero();
        assertAggregate(fixture.product().getId(), 0, 0.0);
    }

    @Test
    void reviewCreationWinning_shouldRetainCommittedReviewAndSnapshotAfterRetirement() throws Exception {
        Fixture fixture = fixture(SecurityConstants.ROLE_USER);
        CountDownLatch reviewCreated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger waiterPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> creation = executor.submit(() -> transaction().executeWithoutResult(status -> {
                authenticatedEmail.set(fixture.user().getEmail());
                productReviewService.addReview(new ProductReviewRequestDTO(fixture.product().getId(), 3, "Committed"));
                reviewCreated.countDown();
                await(commit);
            }));
            assertThat(reviewCreated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<?> retirement = executor.submit(() -> transaction().executeWithoutResult(status -> {
                waiterPid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                userService.delete(fixture.user().getId());
            }));
            assertThat(awaitAdvisoryWait(waiterPid)).isTrue();
            commit.countDown();
            creation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            retirement.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            commit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(physicalReviewCount(fixture.product().getId())).isOne();
        assertThat(productReviewService.getProductReviews(fixture.product().getId(),
                org.springframework.data.domain.Pageable.unpaged()).getContent())
                .singleElement().extracting(response -> response.authorName()).isEqualTo("Review Author");
        assertAggregate(fixture.product().getId(), 1, 3.0);
    }

    @Test
    void administrator_shouldDeleteRetiredAuthorsReviewAndRecomputeAggregate() {
        Fixture author = fixture(SecurityConstants.ROLE_USER);
        authenticatedEmail.set(author.user().getEmail());
        UUID reviewId = productReviewService.addReview(
                new ProductReviewRequestDTO(author.product().getId(), 4, "Moderate me")).id();
        userService.delete(author.user().getId());

        User admin = user("admin", SecurityConstants.ROLE_ADMIN);
        authenticatedEmail.set(admin.getEmail());
        productReviewService.deleteReview(reviewId);

        assertThat(jdbcTemplate.queryForObject("select deleted from product_reviews where id = ?", Boolean.class,
                reviewId)).isTrue();
        assertAggregate(author.product().getId(), 0, 0.0);
    }

    private Fixture fixture(String role) {
        String suffix = UUID.randomUUID().toString();
        Category category = categoryRepository.saveAndFlush(new Category(
                "Review lifecycle " + suffix, "review-lifecycle-" + suffix, "Lifecycle"));
        Product product = productRepository.saveAndFlush(new Product(
                "Review lifecycle product", "review-product-" + suffix, "REVIEW-" + suffix,
                "Lifecycle product", BigDecimal.TEN, 10, category));
        return new Fixture(user("author-" + suffix, role), product);
    }

    private User user(String localPart, String role) {
        User user = new User(localPart + "@example.com", "encoded", "Review", "Author");
        user.addRole(roleRepository.findByName(role).orElseThrow());
        return userRepository.saveAndFlush(user);
    }

    private long physicalReviewCount(UUID productId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from product_reviews where product_id = ? and deleted = false",
                Long.class, productId);
    }

    private void assertAggregate(UUID productId, int count, double average) {
        var aggregate = jdbcTemplate.queryForMap(
                "select review_count, average_rating from products where id = ?", productId);
        assertThat(((Number) aggregate.get("review_count")).intValue()).isEqualTo(count);
        assertThat(((Number) aggregate.get("average_rating")).doubleValue()).isEqualTo(average);
    }

    private boolean awaitAdvisoryWait(AtomicInteger pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (select 1 from pg_locks locks
                    join pg_stat_activity activity on activity.pid = locks.pid
                    where locks.pid = ? and locks.locktype = 'advisory' and not locks.granted
                    and activity.wait_event_type = 'Lock' and activity.wait_event = 'advisory')
                    """, Boolean.class, pid.get()))) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("coordination timeout");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(User user, Product product) { }
}
