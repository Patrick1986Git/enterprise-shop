package com.company.shop.module.notification.delivery;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.company.shop.module.notification.entity.Notification;

public class SmtpNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final NotificationSmtpProperties properties;

    public SmtpNotificationSender(JavaMailSender mailSender, NotificationSmtpProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(Notification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(notification.getRecipient());
        message.setFrom(properties.from());
        message.setSubject(notification.getSubject());
        message.setText(notification.getBody());

        mailSender.send(message);
    }
}
