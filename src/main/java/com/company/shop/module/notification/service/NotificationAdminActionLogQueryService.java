package com.company.shop.module.notification.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.notification.dto.NotificationAdminActionLogResponseDTO;
import com.company.shop.module.notification.exception.NotificationNotFoundException;
import com.company.shop.module.notification.mapper.NotificationAdminActionLogMapper;
import com.company.shop.module.notification.repository.NotificationAdminActionLogRepository;
import com.company.shop.module.notification.repository.NotificationRepository;

@Service
public class NotificationAdminActionLogQueryService {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final NotificationRepository notificationRepository;
    private final NotificationAdminActionLogRepository notificationAdminActionLogRepository;
    private final NotificationAdminActionLogMapper notificationAdminActionLogMapper;

    public NotificationAdminActionLogQueryService(
            NotificationRepository notificationRepository,
            NotificationAdminActionLogRepository notificationAdminActionLogRepository,
            NotificationAdminActionLogMapper notificationAdminActionLogMapper) {
        this.notificationRepository = notificationRepository;
        this.notificationAdminActionLogRepository = notificationAdminActionLogRepository;
        this.notificationAdminActionLogMapper = notificationAdminActionLogMapper;
    }

    @Transactional(readOnly = true)
    public Page<NotificationAdminActionLogResponseDTO> getNotificationActionLogs(UUID notificationId, Pageable pageable) {
        if (!notificationRepository.existsById(notificationId)) {
            throw new NotificationNotFoundException(notificationId);
        }

        return notificationAdminActionLogRepository.findByNotificationId(notificationId, withDefaultSort(pageable))
                .map(notificationAdminActionLogMapper::toDto);
    }

    private Pageable withDefaultSort(Pageable pageable) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        if (pageable.isUnpaged()) {
            return Pageable.unpaged(DEFAULT_SORT);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), DEFAULT_SORT);
    }
}
