package com.company.shop.module.notification.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.notification.dto.NotificationResponseDTO;
import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.entity.NotificationStatus;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.exception.NotificationRequeueNotAllowedException;
import com.company.shop.module.notification.mapper.NotificationMapper;
import com.company.shop.module.notification.repository.NotificationRepository;

@Service
public class NotificationAdminCommandService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;

    public NotificationAdminCommandService(
            NotificationRepository notificationRepository,
            NotificationMapper notificationMapper) {
        this.notificationRepository = notificationRepository;
        this.notificationMapper = notificationMapper;
    }

    @Transactional
    public NotificationResponseDTO requeueFailedNotification(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));

        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new NotificationRequeueNotAllowedException();
        }

        notification.requeueForDelivery();
        return notificationMapper.toDto(notification);
    }
}
