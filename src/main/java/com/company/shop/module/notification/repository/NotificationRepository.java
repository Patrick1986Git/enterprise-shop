package com.company.shop.module.notification.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.company.shop.module.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
