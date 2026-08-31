package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class LegacyReservationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Mock OrderRepository orderRepository;
    @Mock ReservationExpirationWorkRepository workRepository;

    @Test
    void adopt_shouldCreateImmediatelyDueInitialWorkWithoutRecoveryAuthorization() {
        Order order = order(null);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(workRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(workRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        LegacyReservationAdoptionResult result = service().adopt(ORDER_ID);

        assertThat(result.adopted()).isTrue();
        assertThat(result.reservationExpiresAt()).isEqualTo(NOW);
        assertThat(result.workStatus()).isEqualTo(ReservationExpirationWorkStatus.PENDING);
        assertThat(order.getReservationExpiresAt()).isEqualTo(NOW);
        var captor = org.mockito.ArgumentCaptor.forClass(ReservationExpirationWork.class);
        verify(workRepository).save(captor.capture());
        assertThat(captor.getValue().getDueAt()).isEqualTo(NOW);
        assertThat(captor.getValue().getNextAttemptAt()).isEqualTo(NOW);
        assertThat(captor.getValue().getAttempts()).isZero();
        assertThat(captor.getValue().isRecoveryAuthorized()).isFalse();
    }

    @Test
    void adopt_shouldReturnAlreadyManagedForRepeatedRequest() {
        Order order = order(NOW.minusSeconds(60));
        ReservationExpirationWork work = new ReservationExpirationWork(ORDER_ID, NOW.minusSeconds(60));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(workRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(work));

        assertThat(service().adopt(ORDER_ID).adopted()).isFalse();
        verify(workRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adopt_shouldFailClosedForInconsistentManagedState() {
        Order order = order(NOW.minusSeconds(60));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(workRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().adopt(ORDER_ID))
                .isInstanceOf(LegacyReservationAdoptionNotAllowedException.class);
        verify(workRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private LegacyReservationService service() {
        return new LegacyReservationService(orderRepository, workRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Order order(Instant deadline) {
        Order order = new Order(UUID.randomUUID(), "legacy@example.com", "legacy-key", deadline);
        try {
            var id = com.company.shop.common.model.BaseEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(order, ORDER_ID);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        return order;
    }
}
