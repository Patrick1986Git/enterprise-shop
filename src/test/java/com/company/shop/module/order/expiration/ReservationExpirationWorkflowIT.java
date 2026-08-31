package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;

import jakarta.persistence.EntityManager;

import com.company.shop.module.cart.api.internal.CartCheckoutFacade;
import com.company.shop.module.cart.entity.Cart;
import com.company.shop.module.cart.repository.CartRepository;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.order.dto.OrderCheckoutRequestDTO;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.entity.PaymentStatus;
import com.company.shop.module.order.exception.PaymentProcessingException;
import com.company.shop.module.order.exception.OrderPaymentNotAllowedException;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.OrderService;
import com.company.shop.module.order.service.PaymentInitializationTransactionService;
import com.company.shop.module.order.service.PaymentService;
import com.company.shop.module.order.service.PaymentTerminalTransitionService;
import com.company.shop.module.order.service.StripeWebhookEventRegistrar;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.repository.ProductRepository;
import com.company.shop.module.product.service.ProductService;
import com.company.shop.module.user.api.internal.CurrentUserFacade;
import com.company.shop.module.user.api.internal.CurrentUserSnapshot;
import com.company.shop.module.user.entity.User;
import com.company.shop.module.user.repository.UserRepository;
import com.company.shop.persistence.support.PostgresContainerSupport;
import com.company.shop.security.CurrentUserProvider;
import com.stripe.model.PaymentIntent;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(ReservationExpirationWorkflowIT.LockTestConfiguration.class)
class ReservationExpirationWorkflowIT extends PostgresContainerSupport {
    @Autowired OrderService orderService;
    @Autowired ReservationExpirationProcessor processor;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;
    @Autowired ProductService productService;
    @Autowired UserRepository userRepository;
    @Autowired CartRepository cartRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PaymentService paymentService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ReservationExpirationProperties expirationProperties;
    @Autowired ReservationExpirationWorkRepository workRepository;
    @Autowired ReservationExpirationClaimService claimService;
    @Autowired ReservationExpirationRecoveryService recoveryService;
    @Autowired StripeWebhookEventRegistrar webhookEventRegistrar;
    @Autowired PaymentTerminalTransitionService terminalTransitions;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired Clock clock;
    @MockitoBean CurrentUserFacade currentUserFacade;
    @MockitoBean CurrentUserProvider currentUserProvider;
    @MockitoBean StripePaymentIntentGateway stripeGateway;
    @MockitoBean ReservationExpirationPoller reservationExpirationPoller;
    @MockitoSpyBean PaymentInitializationTransactionService paymentInitialization;
    @MockitoSpyBean CartCheckoutFacade cartCheckoutFacade;
    @Autowired OrderLockCoordinator orderLockCoordinator;
    @Autowired LegacyReservationService legacyReservationService;
    @Autowired EntityManager entityManager;

    @Test
    void legacyReservation_shouldBeDiscoverableAndAdoptedWithoutProviderOrInventoryMutation() throws Exception {
        Fixture legacy = checkout("legacy-adoption", 2);
        Fixture managed = checkout("managed-not-legacy", 1);
        makeLegacy(legacy.order());
        clearInvocations(stripeGateway);
        int stockBefore = stock(legacy.product().getId());

        var discovered = legacyReservationService.findUnmanaged(PageRequest.of(0, 20));
        assertThat(discovered).extracting(LegacyReservationResponseDTO::orderId).contains(legacy.order().getId());
        assertThat(discovered).extracting(LegacyReservationResponseDTO::orderId).doesNotContain(managed.order().getId());
        LegacyReservationResponseDTO legacyState = discovered.stream()
                .filter(candidate -> candidate.orderId().equals(legacy.order().getId())).findFirst().orElseThrow();
        assertThat(legacyState.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(legacyState.providerPaymentAttached()).isTrue();

        LegacyReservationAdoptionResult result = legacyReservationService.adopt(legacy.order().getId());

        assertThat(result.adopted()).isTrue();
        assertThat(orderRepository.findById(legacy.order().getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(orderRepository.findById(legacy.order().getId()).orElseThrow().getReservationExpiresAt()).isNotNull();
        ReservationExpirationWork work = workRepository.findByOrderId(legacy.order().getId()).orElseThrow();
        assertThat(work.getNextAttemptAt()).isEqualTo(work.getDueAt());
        assertThat(work.getAttempts()).isZero();
        assertThat(work.isRecoveryAuthorized()).isFalse();
        assertThat(stock(legacy.product().getId())).isEqualTo(stockBefore);
        verifyNoInteractions(stripeGateway);
    }

    @Test
    void concurrentLegacyReservationAdoption_shouldConvergeToExactlyOneWorkRow() throws Exception {
        Fixture fixture = checkout("legacy-concurrent-adoption", 1);
        makeLegacy(fixture.order());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> adoptAfter(start, fixture.order().getId()), executor);
            var second = CompletableFuture.supplyAsync(() -> adoptAfter(start, fixture.order().getId()), executor);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .extracting(LegacyReservationAdoptionResult::adopted)
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservation_expiration_work WHERE order_id = ?", Integer.class,
                fixture.order().getId())).isOne();
    }

    @Test
    void claimLeaseRecovery_shouldEnforceAutomaticAndAdminAuthorizedAttemptBudgets() throws Exception {
        Fixture fixture = checkout("claim-budget", 1);
        makeDue(fixture.order());
        ReservationExpirationWork original = workRepository.findByOrderId(fixture.order().getId()).orElseThrow();
        UUID workId = original.getId();
        int configuredMaxAttempts = expirationProperties.maxAttempts();
        expirationProperties.setMaxAttempts(2);
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");
        clearInvocations(stripeGateway);

        try {
            ReservationExpirationClaim first = claimService.claim(workId).orElseThrow();
            ReservationExpirationWork active = workRepository.findById(workId).orElseThrow();
            Instant activeUntil = active.getClaimUntil();
            assertThat(claimService.claim(workId)).isEmpty();
            assertClaim(workId, first.claimToken(), 1, ReservationExpirationWorkStatus.CLAIMED);
            assertThat(workRepository.findById(workId).orElseThrow().getClaimUntil()).isEqualTo(activeUntil);
            expireClaim(workId);
            ReservationExpirationClaim second = claimService.claim(workId).orElseThrow();

            assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
            assertThatThrownBy(() -> claimService.complete(first)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> claimService.retry(first, "stale retry")).isInstanceOf(IllegalStateException.class);
            assertClaim(workId, second.claimToken(), 2, ReservationExpirationWorkStatus.CLAIMED);

            expireClaim(workId);
            assertThat(claimService.claim(workId)).isEmpty();
            assertThat(claimService.claim(workId)).isEmpty();
            ReservationExpirationWork exhausted = workRepository.findById(workId).orElseThrow();
            assertThat(exhausted.getStatus()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
            assertThat(exhausted.getAttempts()).isEqualTo(2);
            assertThat(exhausted.getClaimToken()).isNull();
            assertThat(exhausted.getClaimUntil()).isNull();
            assertThat(exhausted.getLastError())
                    .isEqualTo(ReservationExpirationClaimService.EXPIRED_CLAIM_BUDGET_EXHAUSTED);
            verifyNoInteractions(stripeGateway);

            ReservationExpirationRecoveryResult recovered = recoveryService.recover(workId);
            assertThat(recovered.status()).isEqualTo(ReservationExpirationWorkStatus.PENDING);
            assertThat(recovered.attempts()).isEqualTo(2);
            assertThat(recovered.recoveryCount()).isOne();
            ReservationExpirationClaim authorized = claimService.claim(workId).orElseThrow();
            assertThat(claimService.retry(authorized, "recovered provider failure")).isTrue();
            ReservationExpirationWork failedRecovery = workRepository.findById(workId).orElseThrow();
            assertThat(failedRecovery.getAttempts()).isEqualTo(3);
            assertThat(failedRecovery.getRecoveryCount()).isOne();
            assertThat(failedRecovery.getLastRecoveredAt()).isNotNull();
            assertThat(failedRecovery.getLastRecoveredBy()).isEqualTo("admin@example.com");

            recoveryService.recover(workId);
            ReservationExpirationClaim crashedRecovery = claimService.claim(workId).orElseThrow();
            expireClaim(workId);
            assertThat(claimService.claim(workId)).isEmpty();
            ReservationExpirationWork crashed = workRepository.findById(workId).orElseThrow();
            assertThat(crashed.getStatus()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
            assertThat(crashed.getAttempts()).isEqualTo(4);
            assertThat(crashed.getRecoveryCount()).isEqualTo(2);
            assertThat(crashed.getClaimToken()).isNull();
            assertThat(crashedRecovery.claimToken()).isNotNull();
            verifyNoInteractions(stripeGateway);
        } finally {
            expirationProperties.setMaxAttempts(configuredMaxAttempts);
        }
    }

    @Test
    void concurrentInitialClaims_shouldProduceOneOwnerAndLeaveUnrelatedWorkClaimable() throws Exception {
        Fixture firstFixture = checkout("competing-claim", 1);
        Fixture unrelatedFixture = checkout("unrelated-claim", 1);
        makeDue(firstFixture.order());
        makeDue(unrelatedFixture.order());
        UUID workId = workRepository.findByOrderId(firstFixture.order().getId()).orElseThrow().getId();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var left = CompletableFuture.supplyAsync(() -> claimService.claim(workId), executor);
            var right = CompletableFuture.supplyAsync(() -> claimService.claim(workId), executor);
            assertThat(java.util.List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                    .filteredOn(java.util.Optional::isPresent).hasSize(1);
        }

        ReservationExpirationWork durable = workRepository.findById(workId).orElseThrow();
        assertThat(durable.getStatus()).isEqualTo(ReservationExpirationWorkStatus.CLAIMED);
        assertThat(durable.getClaimToken()).isNotNull();
        assertThat(durable.getAttempts()).isOne();
        UUID unrelatedWorkId = workRepository.findByOrderId(unrelatedFixture.order().getId()).orElseThrow().getId();
        assertThat(claimService.claim(unrelatedWorkId)).isPresent();
        assertThat(workRepository.findById(unrelatedWorkId).orElseThrow().getAttempts()).isOne();
    }

    @Test
    void historicalOverBudgetFailure_shouldReceiveExactlyOneClaimPerAdminRecovery() throws Exception {
        Fixture fixture = checkout("historical-over-budget", 1);
        makeDue(fixture.order());
        UUID workId = workRepository.findByOrderId(fixture.order().getId()).orElseThrow().getId();
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");
        clearInvocations(stripeGateway);
        jdbcTemplate.update("""
                UPDATE reservation_expiration_work
                SET status = 'FAILED', attempts = 12, claim_token = NULL, claim_until = NULL,
                    failed_at = CURRENT_TIMESTAMP, last_error = 'historical exhausted failure',
                    recovery_count = 0, last_recovered_at = NULL, last_recovered_by = NULL,
                    recovery_authorized = FALSE
                WHERE id = ?
                """, workId);

        ReservationExpirationRecoveryResult firstRecovery = recoveryService.recover(workId);

        assertThat(firstRecovery.status()).isEqualTo(ReservationExpirationWorkStatus.PENDING);
        assertThat(firstRecovery.attempts()).isEqualTo(12);
        assertThat(firstRecovery.recoveryCount()).isOne();
        ReservationExpirationWork authorized = workRepository.findById(workId).orElseThrow();
        assertThat(authorized.isRecoveryAuthorized()).isTrue();
        assertThat(authorized.getLastRecoveredAt()).isNotNull();
        assertThat(authorized.getLastRecoveredBy()).isEqualTo("admin@example.com");

        ReservationExpirationClaim firstAuthorizedClaim = claimService.claim(workId).orElseThrow();
        ReservationExpirationWork claimed = workRepository.findById(workId).orElseThrow();
        assertThat(claimed.getAttempts()).isEqualTo(13);
        assertThat(claimed.getRecoveryCount()).isOne();
        assertThat(claimed.isRecoveryAuthorized()).isFalse();
        assertThat(claimService.claim(workId)).isEmpty();

        expireClaim(workId);
        assertThat(claimService.claim(workId)).isEmpty();
        ReservationExpirationWork failedAfterCrash = workRepository.findById(workId).orElseThrow();
        assertThat(failedAfterCrash.getStatus()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
        assertThat(failedAfterCrash.getAttempts()).isEqualTo(13);
        assertThat(failedAfterCrash.getRecoveryCount()).isOne();
        assertThat(failedAfterCrash.getClaimToken()).isNull();
        assertThat(failedAfterCrash.getClaimUntil()).isNull();
        assertThat(failedAfterCrash.getLastError())
                .isEqualTo(ReservationExpirationClaimService.EXPIRED_CLAIM_BUDGET_EXHAUSTED);
        assertThat(firstAuthorizedClaim.claimToken()).isNotNull();
        verifyNoInteractions(stripeGateway);

        ReservationExpirationRecoveryResult secondRecovery = recoveryService.recover(workId);
        assertThat(secondRecovery.attempts()).isEqualTo(13);
        assertThat(secondRecovery.recoveryCount()).isEqualTo(2);
        ReservationExpirationClaim secondAuthorizedClaim = claimService.claim(workId).orElseThrow();
        assertThat(secondAuthorizedClaim.claimToken()).isNotEqualTo(firstAuthorizedClaim.claimToken());
        ReservationExpirationWork secondClaimed = workRepository.findById(workId).orElseThrow();
        assertThat(secondClaimed.getAttempts()).isEqualTo(14);
        assertThat(secondClaimed.getRecoveryCount()).isEqualTo(2);
        assertThat(secondClaimed.isRecoveryAuthorized()).isFalse();
    }

    @ParameterizedTest(name = "expirationWins={0}")
    @ValueSource(booleans = {true, false})
    void expirationSucceededAndWebhook_shouldConvergeOnceAcrossBothOrderLockWinners(boolean expirationWins)
            throws Exception {
        Fixture fixture = checkout("expiration-success-race", 2);
        Order order = fixture.order();
        makeDue(order);
        PaymentIntent succeeded = terminalIntent("pi_expiration-success-race", "succeeded", order);
        when(stripeGateway.retrieve("pi_expiration-success-race")).thenReturn(succeeded);
        clearInvocations(cartCheckoutFacade);

        String eventId = "evt_expiration_success_race_" + UUID.randomUUID();
        runExpirationAgainstWebhookLockRace(order.getId(), eventId, "payment_intent.succeeded", expirationWins,
                () -> terminalTransitions.convergeSucceeded(order.getId(), succeeded));

        assertTerminalState(fixture, OrderStatus.PAID, PaymentStatus.COMPLETED, 0, eventId);
        verify(cartCheckoutFacade, times(1)).reconcileCartAfterSuccessfulPayment(eq(fixture.user().getId()), any());
    }

    @ParameterizedTest(name = "expirationWins={0}")
    @ValueSource(booleans = {true, false})
    void expirationCanceledAndWebhook_shouldReleaseInventoryOnceAcrossBothOrderLockWinners(boolean expirationWins)
            throws Exception {
        Fixture fixture = checkout("expiration-cancel-race", 2);
        Order order = fixture.order();
        makeDue(order);
        PaymentIntent canceled = terminalIntent("pi_expiration-cancel-race", "canceled", order);
        when(stripeGateway.retrieve("pi_expiration-cancel-race")).thenReturn(canceled);
        clearInvocations(cartCheckoutFacade);

        String eventId = "evt_expiration_cancel_race_" + UUID.randomUUID();
        runExpirationAgainstWebhookLockRace(order.getId(), eventId, "payment_intent.canceled", expirationWins,
                () -> terminalTransitions.convergeCanceled(order.getId(), canceled));

        assertTerminalState(fixture, OrderStatus.CANCELLED, PaymentStatus.FAILED, 2, eventId);
        verify(cartCheckoutFacade, times(0)).reconcileCartAfterSuccessfulPayment(any(), any());
    }

    private void runExpirationAgainstWebhookLockRace(UUID orderId, String eventId, String eventType,
            boolean expirationWins, Runnable webhookTransition) throws Exception {
        CountDownLatch winnerHasOrderLock = new CountDownLatch(1);
        CountDownLatch loserAttemptedOrderLock = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        orderLockCoordinator.arm(orderId, expirationWins, winnerHasOrderLock, loserAttemptedOrderLock, releaseWinner);

        try (ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("reservation-race-" + UUID.randomUUID());
            return thread;
        })) {
            Runnable expirationTask = () -> {
                Thread.currentThread().setName("expiration-race");
                processor.processDueBatch();
            };
            Runnable webhookTask = () -> {
                Thread.currentThread().setName("webhook-race");
                transactionTemplate.executeWithoutResult(status -> {
                    assertThat(webhookEventRegistrar.register(eventId, eventType)).isTrue();
                    webhookTransition.run();
                });
            };
            CompletableFuture<Void> winner = CompletableFuture.runAsync(
                    expirationWins ? expirationTask : webhookTask, executor);
            assertThat(winnerHasOrderLock.await(10, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> loser = CompletableFuture.runAsync(
                    expirationWins ? webhookTask : expirationTask, executor);
            assertThat(loserAttemptedOrderLock.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(loser).as("losing terminal path must be blocked behind the winning Order lock").isNotDone();

            releaseWinner.countDown();
            winner.get(10, TimeUnit.SECONDS);
            loser.get(10, TimeUnit.SECONDS);
        } finally {
            orderLockCoordinator.disarm();
            releaseWinner.countDown();
        }
    }

    private void assertTerminalState(Fixture fixture, OrderStatus orderStatus, PaymentStatus paymentStatus,
            int expectedStock, String eventId) {
        assertThat(orderRepository.findById(fixture.order().getId()).orElseThrow().getStatus()).isEqualTo(orderStatus);
        assertThat(paymentRepository.findByOrderId(fixture.order().getId()).orElseThrow().getStatus())
                .isEqualTo(paymentStatus);
        assertThat(stock(fixture.product().getId())).isEqualTo(expectedStock);
        ReservationExpirationWork work = workRepository.findByOrderId(fixture.order().getId()).orElseThrow();
        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.COMPLETED);
        assertThat(work.getAttempts()).isOne();
        assertThat(work.getClaimToken()).isNull();
        assertThat(work.getLastError()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stripe_webhook_events WHERE stripe_event_id = ?", Long.class, eventId)).isOne();
    }

    @Test
    void paymentInitialization_shouldConvergeOnOriginalIntentAfterProviderSuccessAndLostAttach() throws Exception {
        CheckoutInput input = checkoutInput("lost-attach-retry", 1);
        PaymentIntent providerIntent = providerIntent("pi_lost_attach_retry", "requires_payment_method");
        when(providerIntent.getClientSecret()).thenReturn("cs_lost_attach_retry");
        AtomicInteger logicalProviderCreations = idempotentProvider(providerIntent, input);

        doThrow(new IllegalStateException("simulated process failure before attach commit"))
                .doCallRealMethod()
                .when(paymentInitialization).attach(any(UUID.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> orderService.placeOrderFromCart(input.checkoutKey(),
                new OrderCheckoutRequestDTO(null, null))).isInstanceOf(PaymentProcessingException.class);

        Order order = orderRepository.findByUserIdAndCheckoutIdempotencyKey(input.user().getId(), input.checkoutKey())
                .orElseThrow();
        var paymentAfterFailure = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(paymentAfterFailure.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentAfterFailure.getProviderPaymentId()).isNull();
        assertThat(paymentAfterFailure.getClientSecret()).isNull();
        assertThat(stock(input.product().getId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_expiration_work WHERE order_id = ?", Long.class, order.getId()))
                .isOne();

        var retry = paymentService.createPaymentIntent(order);

        var attached = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(retry.clientSecret()).isEqualTo("cs_lost_attach_retry");
        assertThat(attached.getProviderPaymentId()).isEqualTo("pi_lost_attach_retry");
        assertThat(attached.getClientSecret()).isEqualTo("cs_lost_attach_retry");
        assertThat(attached.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(logicalProviderCreations).hasValue(1);
        verify(stripeGateway, times(2)).create(eq(order.getId()), eq(order.getTotalAmount()),
                eq("order-payment-intent-" + order.getId()));
    }

    @Test
    void expiration_shouldRecoverOriginalIntentAfterProviderSuccessAndLostAttach() throws Exception {
        CheckoutInput input = checkoutInput("lost-attach-expiration", 1);
        PaymentIntent providerIntent = canceledProviderIntent("pi_lost_attach_expiration", BigDecimal.TEN);
        when(providerIntent.getClientSecret()).thenReturn("cs_lost_attach_expiration");
        AtomicInteger logicalProviderCreations = idempotentProvider(providerIntent, input);
        when(stripeGateway.retrieve("pi_lost_attach_expiration")).thenReturn(providerIntent);
        doThrow(new IllegalStateException("simulated process failure before attach commit"))
                .doCallRealMethod()
                .when(paymentInitialization).attach(any(UUID.class), any(String.class), any(String.class));

        assertThatThrownBy(() -> orderService.placeOrderFromCart(input.checkoutKey(),
                new OrderCheckoutRequestDTO(null, null))).isInstanceOf(PaymentProcessingException.class);
        Order order = orderRepository.findByUserIdAndCheckoutIdempotencyKey(input.user().getId(), input.checkoutKey())
                .orElseThrow();
        makeDue(order);

        processor.processDueBatch();

        var payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getProviderPaymentId()).isEqualTo("pi_lost_attach_expiration");
        assertThat(payment.getClientSecret()).isEqualTo("cs_lost_attach_expiration");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stock(input.product().getId())).isEqualTo(1);
        assertThat(logicalProviderCreations).hasValue(1);
        verify(stripeGateway, times(2)).create(eq(order.getId()), eq(order.getTotalAmount()),
                eq("order-payment-intent-" + order.getId()));
        verify(stripeGateway).retrieve("pi_lost_attach_expiration");
    }

    @Test
    void expiration_shouldCancelProviderThenRestoreInventoryAndRejectSameCheckoutKey() throws Exception {
        Instant beforeCheckout = clock.instant();
        Fixture fixture = checkout("expiration", 1);
        Instant afterCheckout = clock.instant();
        Order order = fixture.order();
        assertThat(order.getReservationExpiresAt()).isNotNull();
        assertThat(order.getReservationExpiresAt()).isBetween(
                beforeCheckout.plus(expirationProperties.duration()),
                afterCheckout.plus(expirationProperties.duration()));
        assertThat(stock(fixture.product().getId())).isZero();

        makeDue(order);
        PaymentIntent canceled = canceledProviderIntent("pi_expiration", order);
        when(stripeGateway.retrieve("pi_expiration")).thenReturn(canceled);
        processor.processDueBatch();

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderId(order.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stock(fixture.product().getId())).isEqualTo(1);
        assertThatThrownBy(() -> orderService.placeOrderFromCart("expiration-key", new OrderCheckoutRequestDTO(null, null)))
                .isInstanceOf(OrderPaymentNotAllowedException.class);
        assertThat(orderRepository.findAll()).filteredOn(o -> o.getUserId().equals(fixture.user().getId())).hasSize(1);
    }

    @Test
    void expiration_shouldRestoreSoftDeletedProductWithoutMakingItCatalogVisible() throws Exception {
        Fixture fixture = checkout("soft-delete-expiration", 2);
        assertThat(stock(fixture.product().getId())).isZero();
        productService.delete(fixture.product().getId());
        assertThat(productRepository.findById(fixture.product().getId())).isEmpty();
        assertThat(stock(fixture.product().getId())).isZero();
        makeDue(fixture.order());
        PaymentIntent canceled = canceledProviderIntent("pi_soft-delete-expiration", fixture.order());
        when(stripeGateway.retrieve("pi_soft-delete-expiration")).thenReturn(canceled);

        processor.processDueBatch();

        assertThat(orderRepository.findById(fixture.order().getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByOrderId(fixture.order().getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(stock(fixture.product().getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM products WHERE id = ?", Boolean.class,
                fixture.product().getId())).isTrue();
        assertThat(productRepository.findById(fixture.product().getId())).isEmpty();
    }

    private Fixture checkout(String suffix, int stock) throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String unique = suffix + "-" + token;
        String sku = "expiry-" + token;
        Category category = categoryRepository.saveAndFlush(new Category(unique, unique, "expiration test"));
        Product product = productRepository.saveAndFlush(new Product(unique, unique, sku, "expiration test",
                BigDecimal.TEN, stock, category));
        User user = userRepository.saveAndFlush(new User(unique + "@example.com", "encoded", "Expiry", "User"));
        Cart cart = new Cart(user); cart.addItem(product, stock); cartRepository.saveAndFlush(cart);
        when(currentUserFacade.getCurrentUser()).thenReturn(new CurrentUserSnapshot(user.getId(), user.getEmail(), Set.of()));
        PaymentIntent provider = providerIntent("pi_" + suffix, "requires_payment_method");
        when(provider.getClientSecret()).thenReturn("cs_" + suffix);
        when(stripeGateway.create(any(UUID.class), any(BigDecimal.class), any(String.class))).thenReturn(provider);
        var response = orderService.placeOrderFromCart(suffix + "-key", new OrderCheckoutRequestDTO(null, null));
        return new Fixture(user, product, orderRepository.findById(response.id()).orElseThrow());
    }
    private void makeLegacy(Order order) {
        jdbcTemplate.update("DELETE FROM reservation_expiration_work WHERE order_id = ?", order.getId());
        jdbcTemplate.update("UPDATE orders SET reservation_expires_at = NULL WHERE id = ?", order.getId());
        entityManager.clear();
    }
    private LegacyReservationAdoptionResult adoptAfter(CountDownLatch start, UUID orderId) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("adoption start gate timed out");
            return legacyReservationService.adopt(orderId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
    private CheckoutInput checkoutInput(String suffix, int stock) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String unique = suffix + "-" + token;
        Category category = categoryRepository.saveAndFlush(new Category(unique, unique, "recovery test"));
        Product product = productRepository.saveAndFlush(new Product(unique, unique, "recovery-" + token,
                "recovery test", BigDecimal.TEN, stock, category));
        User user = userRepository.saveAndFlush(new User(unique + "@example.com", "encoded", "Recovery", "User"));
        Cart cart = new Cart(user);
        cart.addItem(product, stock);
        cartRepository.saveAndFlush(cart);
        when(currentUserFacade.getCurrentUser()).thenReturn(
                new CurrentUserSnapshot(user.getId(), user.getEmail(), Set.of()));
        return new CheckoutInput(user, product, suffix + "-key", BigDecimal.TEN.multiply(BigDecimal.valueOf(stock)));
    }
    private AtomicInteger idempotentProvider(PaymentIntent intent, CheckoutInput input) throws Exception {
        AtomicReference<UUID> firstOrderId = new AtomicReference<>();
        AtomicReference<String> firstKey = new AtomicReference<>();
        AtomicInteger logicalCreations = new AtomicInteger();
        when(stripeGateway.create(any(UUID.class), any(BigDecimal.class), any(String.class))).thenAnswer(invocation -> {
            UUID orderId = invocation.getArgument(0);
            BigDecimal amount = invocation.getArgument(1);
            String key = invocation.getArgument(2);
            assertThat(amount).isEqualByComparingTo(input.amount());
            if (firstKey.compareAndSet(null, key)) {
                firstOrderId.set(orderId);
                logicalCreations.incrementAndGet();
            } else {
                assertThat(orderId).isEqualTo(firstOrderId.get());
                assertThat(key).isEqualTo(firstKey.get());
            }
            return intent;
        });
        return logicalCreations;
    }
    private PaymentIntent providerIntent(String id, String status) {
        PaymentIntent intent = mock(PaymentIntent.class); when(intent.getId()).thenReturn(id); when(intent.getStatus()).thenReturn(status); return intent;
    }
    private PaymentIntent canceledProviderIntent(String id, Order order) {
        return canceledProviderIntent(id, order.getTotalAmount());
    }
    private PaymentIntent canceledProviderIntent(String id, BigDecimal amount) {
        PaymentIntent intent = providerIntent(id, "canceled");
        when(intent.getAmount()).thenReturn(amount.movePointRight(2).longValueExact());
        when(intent.getCurrency()).thenReturn("pln");
        return intent;
    }
    private PaymentIntent terminalIntent(String id, String status, Order order) {
        PaymentIntent intent = providerIntent(id, status);
        when(intent.getAmount()).thenReturn(order.getTotalAmount().movePointRight(2).longValueExact());
        when(intent.getAmountReceived()).thenReturn("succeeded".equals(status)
                ? order.getTotalAmount().movePointRight(2).longValueExact() : 0L);
        when(intent.getCurrency()).thenReturn("pln");
        return intent;
    }
    private void makeDue(Order order) {
        jdbcTemplate.update("UPDATE orders SET reservation_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", order.getId());
        jdbcTemplate.update("UPDATE reservation_expiration_work SET due_at = CURRENT_TIMESTAMP - INTERVAL '1 second', next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE order_id = ?", order.getId());
    }
    private void expireClaim(UUID workId) {
        jdbcTemplate.update("UPDATE reservation_expiration_work SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                workId);
    }
    private void assertClaim(UUID workId, UUID token, int attempts, ReservationExpirationWorkStatus status) {
        ReservationExpirationWork work = workRepository.findById(workId).orElseThrow();
        assertThat(work.getStatus()).isEqualTo(status);
        assertThat(work.getClaimToken()).isEqualTo(token);
        assertThat(work.getAttempts()).isEqualTo(attempts);
    }
    private int stock(UUID productId) { return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId); }
    private record Fixture(User user, Product product, Order order) {}
    private record CheckoutInput(User user, Product product, String checkoutKey, BigDecimal amount) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class LockTestConfiguration {
        @Bean
        OrderLockCoordinator orderLockCoordinator() {
            return new OrderLockCoordinator();
        }

        @Bean
        @Primary
        OrderRepository lockObservingOrderRepository(
                @Qualifier("orderRepository") OrderRepository delegate,
                OrderLockCoordinator coordinator) {
            return (OrderRepository) Proxy.newProxyInstance(
                    OrderRepository.class.getClassLoader(),
                    new Class<?>[] {OrderRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("findByIdForUpdate") && args != null && args.length == 1) {
                            return coordinator.invoke((UUID) args[0], () -> invokeDelegate(delegate, method, args));
                        }
                        return invokeDelegate(delegate, method, args);
                    });
        }

        private static Object invokeDelegate(OrderRepository delegate, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }

    static final class OrderLockCoordinator {
        private volatile LockControl control;

        void arm(UUID orderId, boolean expirationWins, CountDownLatch winnerHasOrderLock,
                CountDownLatch loserAttemptedOrderLock, CountDownLatch releaseWinner) {
            control = new LockControl(orderId, expirationWins, winnerHasOrderLock,
                    loserAttemptedOrderLock, releaseWinner);
        }

        void disarm() {
            control = null;
        }

        Object invoke(UUID orderId, ThrowingSupplier delegate) throws Throwable {
            LockControl current = control;
            if (current == null || !current.orderId().equals(orderId)) return delegate.get();
            boolean expirationThread = Thread.currentThread().getName().startsWith("expiration-race");
            boolean winnerThread = expirationThread == current.expirationWins();
            if (!winnerThread) current.loserAttemptedOrderLock().countDown();
            Object result = delegate.get();
            if (winnerThread) {
                current.winnerHasOrderLock().countDown();
                if (!current.releaseWinner().await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out while holding the winning Order lock");
                }
            }
            return result;
        }
    }

    private record LockControl(UUID orderId, boolean expirationWins, CountDownLatch winnerHasOrderLock,
            CountDownLatch loserAttemptedOrderLock, CountDownLatch releaseWinner) {}

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Throwable;
    }
}
