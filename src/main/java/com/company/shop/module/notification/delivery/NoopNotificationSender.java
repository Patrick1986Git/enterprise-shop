package com.company.shop.module.notification.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import com.company.shop.module.notification.entity.Notification;

@Component
@ConditionalOnMissingBean(NotificationSender.class)
public class NoopNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NoopNotificationSender.class);

    @Override
    public void send(Notification notification) {
        log.info("Skipping notification delivery for notification {} because no sender is configured", notification.getId());
    }
}
