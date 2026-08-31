package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.repository.OrderRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import com.company.shop.security.CurrentUserProvider;

@ExtendWith(MockitoExtension.class)
class LegacyReservationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Mock OrderRepository orderRepository;
    @Mock ReservationExpirationWorkRepository workRepository;
    @Mock ReservationExpirationAdminActionLogRepository actionLogRepository;
    @Mock CurrentUserProvider currentUserProvider;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(currentUserProvider.getCurrentUserEmail())
                .thenReturn("admin@example.com");
    }

    @Test
    void adopt_shouldCreateImmediatelyDueInitialWorkWithoutRecoveryAuthorization() {
        Order order = order(null);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(workRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        when(workRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ReservationExpirationWork work = invocation.getArgument(0);
            setId(work, UUID.fromString("00000000-0000-0000-0000-000000000102"));
            return work;
        });
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("  admin@example.com  ");

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
        var logCaptor = org.mockito.ArgumentCaptor.forClass(ReservationExpirationAdminActionLog.class);
        verify(actionLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getOrderId()).isEqualTo(ORDER_ID);
        assertThat(logCaptor.getValue().getWorkId()).isEqualTo(result.workId());
        assertThat(logCaptor.getValue().getActorEmail()).isEqualTo("admin@example.com");
        assertThat(logCaptor.getValue().getOutcome()).isEqualTo(ReservationExpirationAdminActionOutcome.ADOPTED);
    }

    @Test
    void adopt_shouldReturnAlreadyManagedForRepeatedRequest() {
        Order order = order(NOW.minusSeconds(60));
        ReservationExpirationWork work = new ReservationExpirationWork(ORDER_ID, NOW.minusSeconds(60));
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(workRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(work));
        when(currentUserProvider.getCurrentUserEmail()).thenReturn("admin@example.com");

        assertThat(service().adopt(ORDER_ID).adopted()).isFalse();
        verify(workRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(actionLogRepository);
    }

    @Test
    void adopt_shouldRejectNullAdminIdentityBeforeLockingOrPersisting() {
        when(currentUserProvider.getCurrentUserEmail()).thenReturn(null);

        assertThatThrownBy(() -> service().adopt(ORDER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("current admin email must not be blank");

        verifyNoInteractions(orderRepository, workRepository, actionLogRepository);
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

    @Test
    void adopt_shouldRejectTerminalOrder() {
        Order order = order(null);
        order.cancelIfNew();
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(workRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().adopt(ORDER_ID))
                .isInstanceOf(LegacyReservationAdoptionNotAllowedException.class);
        verify(workRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adopt_shouldFailClosedWhenWorkExistsWithoutDeadline() {
        Order order = order(null);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(workRepository.findByOrderId(ORDER_ID))
                .thenReturn(Optional.of(new ReservationExpirationWork(ORDER_ID, NOW)));

        assertThatThrownBy(() -> service().adopt(ORDER_ID))
                .isInstanceOf(LegacyReservationAdoptionNotAllowedException.class);
        verify(workRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void findUnmanaged_shouldApplyDeterministicDefaultSort() {
        assertSort(PageRequest.of(2, 5), Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
    }

    @Test
    void findUnmanaged_shouldAppendAscendingIdTieBreakerToSupportedSort() {
        assertSort(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
    }

    @Test
    void findUnmanaged_shouldPreserveExplicitIdDirectionWithoutDuplication() {
        assertSort(PageRequest.of(0, 20,
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void findUnmanaged_shouldRejectUnsupportedSortBeforeRepositoryExecution() {
        assertThatThrownBy(() -> service().findUnmanaged(
                PageRequest.of(0, 20, Sort.by("paymentStatus"))))
                .isInstanceOf(LegacyReservationSortInvalidException.class);
        verifyNoInteractions(orderRepository);
    }

    private LegacyReservationService service() {
        return new LegacyReservationService(orderRepository, workRepository, actionLogRepository,
                currentUserProvider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void assertSort(Pageable requested, Sort expected) {
        when(orderRepository.findLegacyUnmanagedReservations(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PageImpl<>(java.util.List.of()));
        service().findUnmanaged(requested);
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findLegacyUnmanagedReservations(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(requested.getPageNumber());
        assertThat(captor.getValue().getPageSize()).isEqualTo(requested.getPageSize());
        assertThat(captor.getValue().getSort()).isEqualTo(expected);
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

    private void setId(Object entity, UUID id) {
        try {
            var field = com.company.shop.common.model.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
