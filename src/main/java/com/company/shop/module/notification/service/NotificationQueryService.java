package com.company.shop.module.notification.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.dto.NotificationSummaryDTO;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
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
                notificationRepository.countScheduledPending(now));
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> getNotifications(
            NotificationStatus status,
            String type,
            String recipient,
            Pageable pageable) {
        return notificationRepository.findAll(
                NotificationSpecifications.adminFilters(status, normalize(type), normalize(recipient)),
                pageable)
                .map(notificationMapper::toDto);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
