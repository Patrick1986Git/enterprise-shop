package com.company.shop.module.notification.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.notification.NotificationAdminSearchCriteria;
import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.dto.NotificationSummaryDTO;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationAttemptsRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationCreatedDateRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationLastAttemptDateRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationLastRequeuedDateRangeInvalidException;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.exception.NotificationSentDateRangeInvalidException;
import com.company.shop.module.notification.mapper.NotificationMapper;
import com.company.shop.module.notification.repository.NotificationRepository;
import com.company.shop.module.notification.repository.NotificationSpecifications;

@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;

    public NotificationQueryService(NotificationRepository notificationRepository, NotificationMapper notificationMapper) {
        this.notificationRepository = notificationRepository;
        this.notificationMapper = notificationMapper;
    }

    @Transactional(readOnly = true)
    public NotificationResponseDTO getNotification(UUID id) {
        return notificationRepository.findById(id)
                .map(notificationMapper::toDto)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public NotificationSummaryDTO getSummary() {
        Instant now = Instant.now();
        return new NotificationSummaryDTO(
                notificationRepository.countByStatus(NotificationStatus.PENDING),
                notificationRepository.countByStatus(NotificationStatus.SENT),
                notificationRepository.countByStatus(NotificationStatus.FAILED),
                notificationRepository.countDuePending(now),
                notificationRepository.countScheduledPending(now),
                notificationRepository.countByRequeueCountGreaterThan(0),
                notificationRepository.sumRequeueCount());
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> getNotifications(
            NotificationAdminSearchCriteria criteria,
            Pageable pageable) {
        validateAttemptsRange(criteria.attemptsMin(), criteria.attemptsMax());
        validateLastAttemptDateRange(criteria.lastAttemptFrom(), criteria.lastAttemptTo());
        validateLastRequeuedDateRange(criteria.lastRequeuedFrom(), criteria.lastRequeuedTo());
        validateCreatedDateRange(criteria.createdFrom(), criteria.createdTo());
        validateSentDateRange(criteria.sentFrom(), criteria.sentTo());

        NotificationAdminSearchCriteria normalizedCriteria = NotificationAdminSearchCriteria.builder()
                .status(criteria.status())
                .deliveryState(criteria.deliveryState())
                .sourceEventId(criteria.sourceEventId())
                .type(normalize(criteria.type()))
                .recipient(normalize(criteria.recipient()))
                .lastErrorContains(normalize(criteria.lastErrorContains()))
                .lastRequeuedBy(normalize(criteria.lastRequeuedBy()))
                .requeuedOnly(criteria.requeuedOnly())
                .attemptsMin(criteria.attemptsMin())
                .attemptsMax(criteria.attemptsMax())
                .lastAttemptFrom(criteria.lastAttemptFrom())
                .lastAttemptTo(criteria.lastAttemptTo())
                .lastRequeuedFrom(criteria.lastRequeuedFrom())
                .lastRequeuedTo(criteria.lastRequeuedTo())
                .createdFrom(criteria.createdFrom())
                .createdTo(criteria.createdTo())
                .sentFrom(criteria.sentFrom())
                .sentTo(criteria.sentTo())
                .build();

        Specification<Notification> specification = normalizedCriteria.deliveryState() == null
                ? NotificationSpecifications.adminFilters(normalizedCriteria)
                : NotificationSpecifications.adminFilters(normalizedCriteria, Instant.now());

        return notificationRepository.findAll(
                specification,
                pageable)
                .map(notificationMapper::toDto);
    }

    private void validateLastRequeuedDateRange(Instant lastRequeuedFrom, Instant lastRequeuedTo) {
        if (lastRequeuedFrom != null && lastRequeuedTo != null && lastRequeuedFrom.isAfter(lastRequeuedTo)) {
            throw new NotificationLastRequeuedDateRangeInvalidException();
        }
    }

    private void validateCreatedDateRange(Instant createdFrom, Instant createdTo) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new NotificationCreatedDateRangeInvalidException();
        }
    }

    private void validateSentDateRange(Instant sentFrom, Instant sentTo) {
        if (sentFrom != null && sentTo != null && sentFrom.isAfter(sentTo)) {
            throw new NotificationSentDateRangeInvalidException();
        }
    }

    private void validateLastAttemptDateRange(Instant lastAttemptFrom, Instant lastAttemptTo) {
        if (lastAttemptFrom != null && lastAttemptTo != null && lastAttemptFrom.isAfter(lastAttemptTo)) {
            throw new NotificationLastAttemptDateRangeInvalidException();
        }
    }

    private void validateAttemptsRange(Integer attemptsMin, Integer attemptsMax) {
        if ((attemptsMin != null && attemptsMin < 0)
                || (attemptsMax != null && attemptsMax < 0)
                || (attemptsMin != null && attemptsMax != null && attemptsMin > attemptsMax)) {
            throw new NotificationAttemptsRangeInvalidException();
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
