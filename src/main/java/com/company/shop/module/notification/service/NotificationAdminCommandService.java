package com.company.shop.module.notification.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationAdminActionLog;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.exception.NotificationRequeueNotAllowedException;
import com.company.shop.module.notification.mapper.NotificationMapper;
import com.company.shop.module.notification.repository.NotificationAdminActionLogRepository;
import com.company.shop.module.notification.repository.NotificationRepository;
import com.company.shop.security.CurrentUserProvider;

@Service
public class NotificationAdminCommandService {

    private final NotificationRepository notificationRepository;
    private final NotificationAdminActionLogRepository notificationAdminActionLogRepository;
    private final NotificationMapper notificationMapper;
    private final CurrentUserProvider currentUserProvider;

    public NotificationAdminCommandService(
            NotificationRepository notificationRepository,
            NotificationAdminActionLogRepository notificationAdminActionLogRepository,
            NotificationMapper notificationMapper,
            CurrentUserProvider currentUserProvider) {
        this.notificationRepository = notificationRepository;
        this.notificationAdminActionLogRepository = notificationAdminActionLogRepository;
        this.notificationMapper = notificationMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional
    public NotificationResponseDTO requeueFailedNotification(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));

        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new NotificationRequeueNotAllowedException();
        }

        String currentAdminEmail = requireText(
                currentUserProvider.getCurrentUserEmail(),
                "Current admin email is required to requeue notification");
        notification.requeueForDelivery(currentAdminEmail);
        notificationAdminActionLogRepository.save(
                NotificationAdminActionLog.requeue(notification.getId(), currentAdminEmail));
        return notificationMapper.toDto(notification);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
