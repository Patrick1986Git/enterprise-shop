package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationWorkQueryServiceTest {
    @Mock
    private ReservationExpirationWorkRepository repository;
    @Mock
    private ReservationExpirationWorkMapper mapper;

    @Test
    void findAll_shouldApplyExactFiltersAndDeterministicDefaultSortWithoutMutatingWork() {
        UUID orderId = UUID.randomUUID();
        ReservationExpirationWork work = new ReservationExpirationWork(orderId, Instant.EPOCH);
        ReservationExpirationWorkResponseDTO dto = dto(work.getId(), orderId);
        when(repository.findAdminWork(nullable(ReservationExpirationWorkStatus.class), nullable(UUID.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(work)));
        when(mapper.toDto(work)).thenReturn(dto);
        ReservationExpirationWorkQueryService service = new ReservationExpirationWorkQueryService(repository, mapper);

        assertThat(service.findAll(ReservationExpirationWorkStatus.FAILED, orderId, PageRequest.of(1, 5)))
                .containsExactly(dto);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAdminWork(eq(ReservationExpirationWorkStatus.FAILED), eq(orderId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getSort()).containsExactly(
                Sort.Order.asc("dueAt"), Sort.Order.asc("id"));
        assertThat(work.getStatus()).isEqualTo(ReservationExpirationWorkStatus.PENDING);
        assertThat(work.getAttempts()).isZero();
        assertThat(work.getRecoveryCount()).isZero();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void findAll_shouldAllowSupportedSortAndAppendStableIdTieBreaker() {
        when(repository.findAdminWork(nullable(ReservationExpirationWorkStatus.class), nullable(UUID.class),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        ReservationExpirationWorkQueryService service = new ReservationExpirationWorkQueryService(repository, mapper);

        service.findAll(null, null, PageRequest.of(0, 20, Sort.Direction.DESC, "failedAt"));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAdminWork(nullable(ReservationExpirationWorkStatus.class), nullable(UUID.class),
                pageable.capture());
        assertThat(pageable.getValue().getSort()).containsExactly(
                Sort.Order.desc("failedAt"), Sort.Order.asc("id"));
    }

    @Test
    void findAll_shouldRejectUnsupportedSort() {
        ReservationExpirationWorkQueryService service = new ReservationExpirationWorkQueryService(repository, mapper);
        assertThatThrownBy(() -> service.findAll(null, null, PageRequest.of(0, 20, Sort.by("claimToken"))))
                .isInstanceOf(ReservationExpirationWorkSortInvalidException.class);
    }

    @Test
    void findById_shouldReturnDtoOrEstablishedNotFoundContract() {
        UUID workId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ReservationExpirationWork work = new ReservationExpirationWork(orderId, Instant.EPOCH);
        ReservationExpirationWorkResponseDTO dto = dto(workId, orderId);
        when(repository.findById(workId)).thenReturn(Optional.of(work));
        when(mapper.toDto(work)).thenReturn(dto);
        ReservationExpirationWorkQueryService service = new ReservationExpirationWorkQueryService(repository, mapper);

        assertThat(service.findById(workId)).isEqualTo(dto);
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findById(missingId))
                .isInstanceOf(ReservationExpirationWorkNotFoundException.class);
    }

    private ReservationExpirationWorkResponseDTO dto(UUID workId, UUID orderId) {
        return new ReservationExpirationWorkResponseDTO(workId, orderId, ReservationExpirationWorkStatus.FAILED,
                Instant.EPOCH, Instant.EPOCH, null, 3, "failed", null, Instant.EPOCH, 0, null, null);
    }
}
