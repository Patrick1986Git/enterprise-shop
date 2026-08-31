package com.company.shop.module.order.expiration;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.security.CurrentUserProvider;
import io.micrometer.core.instrument.MeterRegistry;

@Service
@ConditionalOnProperty(name = "spring.datasource.url")
public class ReservationExpirationRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationRecoveryService.class);
    private final ReservationExpirationWorkRepository workRepository;
    private final OrderRepository orderRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ReservationExpirationAdminActionLogRepository actionLogRepository;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReservationExpirationRecoveryService(ReservationExpirationWorkRepository workRepository,
            OrderRepository orderRepository, CurrentUserProvider currentUserProvider,
            ReservationExpirationAdminActionLogRepository actionLogRepository,
            MeterRegistry meters, Clock clock) {
        this.workRepository = workRepository;
        this.orderRepository = orderRepository;
        this.currentUserProvider = currentUserProvider;
        this.actionLogRepository = actionLogRepository;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional
    public ReservationExpirationRecoveryResult recover(UUID workId) {
        ReservationExpirationWork work = workRepository.findByIdForUpdate(workId)
                .orElseThrow(() -> new ReservationExpirationWorkNotFoundException(workId));
        if (work.getStatus() != ReservationExpirationWorkStatus.FAILED) {
            throw new ReservationExpirationRecoveryNotAllowedException();
        }
        String actor = normalizedActor();
        Order order = orderRepository.findByIdForUpdate(work.getOrderId()).orElse(null);
        String outcome;
        ReservationExpirationAdminActionOutcome auditOutcome;
        if (order == null || order.getStatus() != OrderStatus.NEW) {
            work.completeFailedForTerminalOrder(clock.instant(), actor);
            outcome = "terminal_noop";
            auditOutcome = ReservationExpirationAdminActionOutcome.TERMINAL_NOOP;
        } else {
            work.requeueFailed(clock.instant(), actor);
            outcome = "requeued";
            auditOutcome = ReservationExpirationAdminActionOutcome.REQUEUED;
        }
        actionLogRepository.save(ReservationExpirationAdminActionLog.recovery(
                work.getOrderId(), work.getId(), auditOutcome, actor, work.getLastRecoveredAt()));
        meters.counter("shop.order.reservation_expiration.recovery.total", "outcome", outcome).increment();
        log.info("Reservation expiration recovery outcome={} workId={} orderId={} actor={}",
                outcome, work.getId(), work.getOrderId(), actor);
        return new ReservationExpirationRecoveryResult(work.getId(), work.getOrderId(), work.getStatus(),
                work.getAttempts(), work.getRecoveryCount(), work.getLastRecoveredAt());
    }

    private String normalizedActor() {
        String actor = currentUserProvider.getCurrentUserEmail();
        actor = actor == null ? null : actor.trim();
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("current admin email must not be blank");
        return actor;
    }
}
