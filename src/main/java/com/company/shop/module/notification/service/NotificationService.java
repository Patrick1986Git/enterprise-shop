package com.company.shop.module.notification.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.company.shop.module.notification.entity.Notification;
import com.company.shop.module.notification.repository.NotificationRepository;

@Service
public class NotificationService {

    private static final String ORDER_PLACED_NOTIFICATION_TYPE = "ORDER_PLACED_EMAIL";

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public Notification createOrderPlacedNotification(
            UUID orderId,
            String userEmail,
            BigDecimal totalAmount,
            UUID sourceEventId) {
        if (sourceEventId != null) {
            return notificationRepository.findBySourceEventId(sourceEventId)
                    .orElseGet(() -> createAndSaveOrderPlacedNotification(
                            orderId,
                            userEmail,
                            totalAmount,
                            sourceEventId));
        }

        return createAndSaveOrderPlacedNotification(orderId, userEmail, totalAmount, null);
    }

    private Notification createAndSaveOrderPlacedNotification(
            UUID orderId,
            String userEmail,
            BigDecimal totalAmount,
            UUID sourceEventId) {
        String subject = "Order placed: " + orderId;
        String body = "Your order " + orderId + " has been placed. Total amount: " + totalAmount + ".";

        Notification notification = Notification.pending(
                ORDER_PLACED_NOTIFICATION_TYPE,
                userEmail,
                subject,
                body,
                sourceEventId);

        return notificationRepository.save(notification);
    }
}
