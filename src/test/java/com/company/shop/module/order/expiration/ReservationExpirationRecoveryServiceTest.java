package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.repository.OrderRepository;
import com.company.shop.security.CurrentUserProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final UUID WORK_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000052");
    @Mock ReservationExpirationWorkRepository workRepository;
    @Mock OrderRepository orderRepository;
    @Mock CurrentUserProvider currentUserProvider;
    @Mock ReservationExpirationAdminActionLogRepository actionLogRepository;
    private SimpleMeterRegistry meters;
    private ReservationExpirationRecoveryService service;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        service = new ReservationExpirationRecoveryService(workRepository, orderRepository, currentUserProvider,
                actionLogRepository, meters, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");
    }

    @Test
    void recover_shouldRequeueFailedWorkWithoutCallingProviderOrReleasingInventory() {
        ReservationExpirationWork work = failedWork();
        Order order = new Order(UUID.randomUUID(), "buyer@example.com", "key", NOW);
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        ReservationExpirationRecoveryResult result = service.recover(WORK_ID);

        assertThat(result.status()).isEqualTo(ReservationExpirationWorkStatus.PENDING);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.recoveryCount()).isEqualTo(1);
        assertThat(work.getLastError()).isEqualTo("provider unavailable");
        assertThat(meters.counter("shop.order.reservation_expiration.recovery.total", "outcome", "requeued").count())
                .isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(ReservationExpirationAdminActionLog.class);
        verify(actionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(ReservationExpirationAdminActionOutcome.REQUEUED);
        assertThat(captor.getValue().getActorEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void recover_shouldTrimAdminIdentityInSummaryAndAudit() {
        ReservationExpirationWork work = failedWork();
        Order order = new Order(UUID.randomUUID(), "buyer@example.com", "key", NOW);
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("  admin@example.com  ");

        service.recover(WORK_ID);

        assertThat(work.getLastRecoveredBy()).isEqualTo("admin@example.com");
        var captor = org.mockito.ArgumentCaptor.forClass(ReservationExpirationAdminActionLog.class);
        verify(actionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActorEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void recover_shouldCompleteFailedWorkWithoutRequeueWhenWebhookAlreadyMadeOrderTerminal() {
        ReservationExpirationWork work = failedWork();
        Order order = new Order(UUID.randomUUID(), "buyer@example.com", "key", NOW);
        order.cancelIfNew();
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        ReservationExpirationRecoveryResult result = service.recover(WORK_ID);

        assertThat(result.status()).isEqualTo(ReservationExpirationWorkStatus.COMPLETED);
        assertThat(meters.counter("shop.order.reservation_expiration.recovery.total", "outcome", "terminal_noop").count())
                .isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(ReservationExpirationAdminActionLog.class);
        verify(actionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(ReservationExpirationAdminActionOutcome.TERMINAL_NOOP);
    }

    @Test
    void recover_shouldDeterministicallyRejectRepeatedRecovery() {
        ReservationExpirationWork work = failedWork();
        Order order = new Order(UUID.randomUUID(), "buyer@example.com", "key", NOW);
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        service.recover(WORK_ID);

        assertThatThrownBy(() -> service.recover(WORK_ID))
                .isInstanceOf(ReservationExpirationRecoveryNotAllowedException.class);
        verify(orderRepository, times(1)).findByIdForUpdate(ORDER_ID);
    }

    @Test
    void recover_shouldRejectMissingWorkWithoutLockingOrder() {
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recover(WORK_ID))
                .isInstanceOf(ReservationExpirationWorkNotFoundException.class)
                .hasMessageContaining(WORK_ID.toString());

        verifyNoInteractions(orderRepository);
    }

    @Test
    void recover_shouldRejectWorkThatIsNotFailedWithoutLockingOrder() {
        ReservationExpirationWork work = new ReservationExpirationWork(ORDER_ID, NOW);
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));

        assertThatThrownBy(() -> service.recover(WORK_ID))
                .isInstanceOf(ReservationExpirationRecoveryNotAllowedException.class);

        verifyNoInteractions(orderRepository);
    }

    @Test
    void recover_shouldRejectMissingAdminIdentityWithoutChangingFailedWork() {
        ReservationExpirationWork work = failedWork();
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("  ");

        assertThatThrownBy(() -> service.recover(WORK_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("current admin email must not be blank");

        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.FAILED);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void recover_shouldCompleteFailedWorkWhenOrderNoLongerExists() {
        ReservationExpirationWork work = failedWork();
        when(workRepository.findByIdForUpdate(WORK_ID)).thenReturn(Optional.of(work));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        ReservationExpirationRecoveryResult result = service.recover(WORK_ID);

        assertThat(result.status()).isEqualTo(ReservationExpirationWorkStatus.COMPLETED);
        assertThat(result.lastRecoveredAt()).isEqualTo(NOW);
    }

    private ReservationExpirationWork failedWork() {
        ReservationExpirationWork work = new ReservationExpirationWork(ORDER_ID, NOW.minusSeconds(60));
        setId(work, WORK_ID);
        UUID token = work.claim(NOW.minusSeconds(30), NOW.minusSeconds(1));
        work.retry(token, NOW.minusSeconds(20), NOW.minusSeconds(10), "provider unavailable", 1);
        return work;
    }

    private void setId(Object entity, UUID id) {
        try {
            var field = com.company.shop.common.model.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
