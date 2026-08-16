package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.Payment;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.PaymentTerminalTransitionService;
import com.stripe.model.PaymentIntent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationProcessorTest {
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final UUID WORK_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000041");
    @Mock ReservationExpirationWorkRepository workRepository;
    @Mock ReservationExpirationClaimService claimService;
    @Mock StripePaymentIntentGateway stripeGateway;
    @Mock PaymentRepository paymentRepository;
    @Mock OrderRepository orderRepository;
    @Mock PaymentTerminalTransitionService transitions;
    private ReservationExpirationProcessor processor;
    private ReservationExpirationClaim claim;
    private PaymentIntent intent;

    @BeforeEach
    void setUp() {
        var properties = new ReservationExpirationProperties();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        processor = new ReservationExpirationProcessor(workRepository, claimService, properties, stripeGateway,
                paymentRepository, orderRepository, transitions, new SimpleMeterRegistry(), clock);
        claim = new ReservationExpirationClaim(WORK_ID, ORDER_ID, UUID.randomUUID());
        when(workRepository.findDueCandidateIds(NOW, 25)).thenReturn(List.of(WORK_ID));
        when(claimService.claim(WORK_ID)).thenReturn(Optional.of(claim));
        Order order = new Order(UUID.randomUUID(), "buyer@example.com", "key", NOW);
        setId(order, ORDER_ID);
        Payment payment = new Payment(order, "STRIPE", BigDecimal.TEN);
        payment.attachProviderPayment("pi_expire", "secret-not-logged");
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(payment));
        intent = mock(PaymentIntent.class);
        lenient().when(intent.getId()).thenReturn("pi_expire");
    }

    @Test
    void processDueBatch_shouldCancelSafelyCancelableIntentWithStableIdempotencyAndConverge() throws Exception {
        when(stripeGateway.retrieve("pi_expire")).thenReturn(intent);
        when(intent.getStatus()).thenReturn("requires_payment_method");
        PaymentIntent canceled = mock(PaymentIntent.class);
        when(canceled.getStatus()).thenReturn("canceled");
        when(stripeGateway.cancelAsAbandoned(intent, "order-reservation-expiration-" + ORDER_ID)).thenReturn(canceled);
        when(transitions.convergeCanceled(ORDER_ID, canceled)).thenReturn(1);

        processor.processDueBatch();

        verify(transitions).convergeCanceled(ORDER_ID, canceled);
        verify(claimService).complete(claim);
        verify(claimService, never()).retry(any(), any());
    }

    @Test
    void processDueBatch_shouldConvergeProviderSuccessWithoutCancellationOrRelease() throws Exception {
        when(stripeGateway.retrieve("pi_expire")).thenReturn(intent);
        when(intent.getStatus()).thenReturn("succeeded");

        processor.processDueBatch();

        verify(transitions).convergeSucceeded(ORDER_ID, intent);
        verify(stripeGateway, never()).cancelAsAbandoned(any(), any());
        verify(transitions, never()).convergeCanceled(any(), any());
        verify(claimService).complete(claim);
    }

    @Test
    void processDueBatch_shouldRetryWithoutMutationWhenProviderIsProcessing() throws Exception {
        when(stripeGateway.retrieve("pi_expire")).thenReturn(intent);
        when(intent.getStatus()).thenReturn("processing");

        processor.processDueBatch();

        verify(claimService).retry(eq(claim), contains("not safely cancelable"));
        verifyNoInteractions(transitions);
    }

    @Test
    void processDueBatch_shouldRetryWithoutMutationOnProviderFailure() throws Exception {
        when(stripeGateway.retrieve("pi_expire")).thenThrow(new IllegalStateException("provider unavailable"));

        processor.processDueBatch();

        verify(claimService).retry(eq(claim), contains("provider unavailable"));
        verifyNoInteractions(transitions);
    }

    @Test
    void processDueBatch_shouldRetryWithoutMutationWhenProviderIdentityMismatches() throws Exception {
        when(stripeGateway.retrieve("pi_expire")).thenReturn(intent);
        when(intent.getId()).thenReturn("pi_different");

        processor.processDueBatch();

        verify(claimService).retry(eq(claim), contains("identity mismatch"));
        verifyNoInteractions(transitions);
        verify(claimService, never()).complete(claim);
    }

    @Test
    void processDueBatch_shouldRetryWithoutMutationWhenCancellationIsNotTerminal() throws Exception {
        when(stripeGateway.retrieve("pi_expire")).thenReturn(intent);
        when(intent.getStatus()).thenReturn("requires_confirmation");
        PaymentIntent stillPending = mock(PaymentIntent.class);
        when(stillPending.getStatus()).thenReturn("requires_confirmation");
        when(stripeGateway.cancelAsAbandoned(intent, "order-reservation-expiration-" + ORDER_ID))
                .thenReturn(stillPending);

        processor.processDueBatch();

        verify(claimService).retry(eq(claim), contains("cancellation was not terminal"));
        verifyNoInteractions(transitions);
        verify(claimService, never()).complete(claim);
    }

    @Test
    void processDueBatch_shouldConvergeAlreadyCanceledIntentWithoutCancelingAgain() throws Exception {
        when(stripeGateway.retrieve("pi_expire")).thenReturn(intent);
        when(intent.getStatus()).thenReturn("canceled");
        when(transitions.convergeCanceled(ORDER_ID, intent)).thenReturn(2);

        processor.processDueBatch();

        verify(transitions).convergeCanceled(ORDER_ID, intent);
        verify(stripeGateway, never()).cancelAsAbandoned(any(), any());
        verify(claimService).complete(claim);
        verify(claimService, never()).retry(any(), any());
    }

    @Test
    void processDueBatch_shouldRetryWhenPersistedProviderIdIsMissing() {
        Payment missing = new Payment(orderRepository.findById(ORDER_ID).orElseThrow(), "STRIPE", BigDecimal.TEN);
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(missing));

        processor.processDueBatch();

        verify(claimService).retry(eq(claim), contains("PaymentIntent id is missing"));
        verifyNoInteractions(stripeGateway, transitions);
    }

    private void setId(Object entity, UUID id) {
        try {
            var field = com.company.shop.common.model.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true); field.set(entity, id);
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
    }
}
