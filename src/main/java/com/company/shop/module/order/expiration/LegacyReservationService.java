package com.company.shop.module.order.expiration;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.order.entity.Order;
import com.company.shop.module.order.entity.OrderStatus;
import com.company.shop.module.order.exception.OrderNotFoundException;
import com.company.shop.module.order.repository.OrderRepository;

@Service
public class LegacyReservationService {
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("createdAt", "id");
    private final OrderRepository orderRepository;
    private final ReservationExpirationWorkRepository workRepository;
    private final Clock clock;

    public LegacyReservationService(OrderRepository orderRepository,
            ReservationExpirationWorkRepository workRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.workRepository = workRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<LegacyReservationResponseDTO> findUnmanaged(Pageable pageable) {
        return orderRepository.findLegacyUnmanagedReservations(deterministic(pageable));
    }

    @Transactional
    public LegacyReservationAdoptionResult adopt(UUID orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        var existingWork = workRepository.findByOrderId(orderId);

        if (order.getStatus() != OrderStatus.NEW) {
            throw new LegacyReservationAdoptionNotAllowedException(orderId, "order is terminal");
        }
        if (order.getReservationExpiresAt() != null && existingWork.isPresent()) {
            ReservationExpirationWork work = existingWork.orElseThrow();
            return result(order, work, false);
        }
        if (order.getReservationExpiresAt() != null || existingWork.isPresent()) {
            throw new LegacyReservationAdoptionNotAllowedException(orderId,
                    "reservation deadline and work state are inconsistent");
        }

        Instant dueAt = clock.instant();
        order.adoptLegacyReservation(dueAt);
        ReservationExpirationWork work = workRepository.save(new ReservationExpirationWork(orderId, dueAt));
        return result(order, work, true);
    }

    private LegacyReservationAdoptionResult result(Order order, ReservationExpirationWork work, boolean adopted) {
        return new LegacyReservationAdoptionResult(order.getId(), work.getId(), order.getReservationExpiresAt(),
                work.getStatus(), adopted);
    }

    private Pageable deterministic(Pageable pageable) {
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Direction.ASC, "createdAt", "id");
        } else {
            for (Sort.Order order : sort) {
                if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                    throw new LegacyReservationSortInvalidException(order.getProperty());
                }
            }
            if (sort.getOrderFor("id") == null) {
                sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
