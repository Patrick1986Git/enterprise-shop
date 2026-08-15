package com.company.shop.module.order.expiration;

import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.module.order.repository.PaymentRepository;
import com.company.shop.module.order.service.PaymentTerminalTransitionService;
import com.stripe.model.PaymentIntent;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class ReservationExpirationProcessor {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationProcessor.class);
    private static final Set<String> CANCELABLE = Set.of("requires_payment_method", "requires_confirmation",
            "requires_action", "requires_capture");
    private final ReservationExpirationWorkRepository workRepository;
    private final ReservationExpirationClaimService claimService;
    private final ReservationExpirationProperties properties;
    private final StripePaymentIntentGateway stripeGateway;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentTerminalTransitionService transitions;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReservationExpirationProcessor(ReservationExpirationWorkRepository workRepository,
            ReservationExpirationClaimService claimService, ReservationExpirationProperties properties,
            StripePaymentIntentGateway stripeGateway, PaymentRepository paymentRepository, OrderRepository orderRepository,
            PaymentTerminalTransitionService transitions, MeterRegistry meters, Clock clock) {
        this.workRepository = workRepository; this.claimService = claimService; this.properties = properties;
        this.stripeGateway = stripeGateway; this.paymentRepository = paymentRepository; this.orderRepository = orderRepository;
        this.transitions = transitions; this.meters = meters; this.clock = clock;
    }
    public void processDueBatch() {
        workRepository.findDueCandidateIds(clock.instant(), properties.batchSize()).forEach(id ->
                claimService.claim(id).ifPresent(this::processClaim));
    }
    private void processClaim(ReservationExpirationClaim claim) {
        metric("claimed");
        try {
            var order = orderRepository.findById(claim.orderId()).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.NEW) { claimService.complete(claim); metric("terminal_noop"); return; }
            var payment = paymentRepository.findByOrderId(claim.orderId()).orElseThrow(
                    () -> new IllegalStateException("Expiration payment record is missing"));
            String providerId = payment.getProviderPaymentId();
            if (providerId == null || providerId.isBlank()) throw new IllegalStateException("Expiration PaymentIntent id is missing");
            PaymentIntent intent = stripeGateway.retrieve(providerId);
            if (!providerId.equals(intent.getId())) throw new IllegalStateException("Expiration PaymentIntent identity mismatch");
            String status = intent.getStatus();
            if ("succeeded".equals(status)) {
                transitions.convergeSucceeded(claim.orderId(), intent); claimService.complete(claim); metric("provider_succeeded"); return;
            }
            if ("canceled".equals(status)) {
                int units = transitions.convergeCanceled(claim.orderId(), intent); released(units); claimService.complete(claim); metric("provider_already_canceled"); return;
            }
            if (CANCELABLE.contains(status)) {
                PaymentIntent canceled = stripeGateway.cancelAsAbandoned(intent, "order-reservation-expiration-" + claim.orderId());
                if (!"canceled".equals(canceled.getStatus())) throw new IllegalStateException("Stripe cancellation was not terminal");
                int units = transitions.convergeCanceled(claim.orderId(), canceled); released(units); claimService.complete(claim); metric("provider_canceled"); return;
            }
            retry(claim, "PaymentIntent is not safely cancelable: " + status, "provider_pending");
        } catch (Exception ex) {
            retry(claim, ex.getClass().getSimpleName() + ": " + ex.getMessage(), "failed");
            log.warn("Reservation expiration reconciliation failed orderId={} workId={}", claim.orderId(), claim.workId(), ex);
        }
    }
    private void retry(ReservationExpirationClaim claim, String error, String outcome) { claimService.retry(claim, error); metric(outcome); metric("retry"); }
    private void metric(String outcome) { meters.counter("shop.order.reservation_expiration.total", "outcome", outcome).increment(); }
    private void released(int units) { meters.counter("shop.order.reservation_expiration.inventory_units_released").increment(units); }
}
