package com.company.shop.module.notification.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.company.shop.module.notification.entity.NotificationAdminActionLog;

public interface NotificationAdminActionLogRepository extends
        JpaRepository<NotificationAdminActionLog, UUID>,
        JpaSpecificationExecutor<NotificationAdminActionLog> {

    List<NotificationAdminActionLog> findByNotificationIdOrderByCreatedAtDesc(UUID notificationId);

    Page<NotificationAdminActionLog> findByNotificationId(UUID notificationId, Pageable pageable);
}
