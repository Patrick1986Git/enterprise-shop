package com.company.shop.module.order.expiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationAdminActionLogQueryServiceTest {
    @Mock ReservationExpirationAdminActionLogRepository repository;
    @Mock ReservationExpirationAdminActionLogMapper mapper;

    @Test
    void search_shouldUseNewestFirstStableDefaultSort() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service().search(null, null, null, null, null, null, null, PageRequest.of(1, 10));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isOne();
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(
                Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void search_shouldAppendDescendingIdTieBreakerToAllowedSort() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service().search(null, null, null, null, null, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt")));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(
                Sort.Order.asc("createdAt"), Sort.Order.desc("id")));
    }

    @Test
    void search_shouldRejectInvalidDateRangeAndUnsupportedSort() {
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        assertThatThrownBy(() -> service().search(null, null, null, null, null,
                now, now.minusSeconds(1), PageRequest.of(0, 20)))
                .isInstanceOf(ReservationExpirationActionLogDateRangeInvalidException.class);
        assertThatThrownBy(() -> service().search(null, null, null, null, null,
                null, null, PageRequest.of(0, 20, Sort.by("actorEmail"))))
                .isInstanceOf(ReservationExpirationActionLogSortInvalidException.class);
        verifyNoInteractions(repository);
    }

    private ReservationExpirationAdminActionLogQueryService service() {
        return new ReservationExpirationAdminActionLogQueryService(repository, mapper);
    }
}
