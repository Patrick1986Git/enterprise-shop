package com.company.shop.module.notification.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.company.shop.module.notification.entity.NotificationAdminActionLog;

public interface NotificationAdminActionLogRepository extends JpaRepository<NotificationAdminActionLog, UUID> {

    List<NotificationAdminActionLog> findByNotificationIdOrderByCreatedAtDesc(UUID notificationId);
}
