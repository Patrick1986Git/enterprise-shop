package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.company.shop.module.notification.entity.Notification;

@ExtendWith(MockitoExtension.class)
class SmtpNotificationSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private NotificationSmtpProperties properties;
    private SmtpNotificationSender sender;

    @BeforeEach
    void setUp() {
        properties = new NotificationSmtpProperties();
        properties.setFrom("orders@example.com");
        sender = new SmtpNotificationSender(mailSender, properties);
    }

    @Test
    void send_shouldSendSimpleMailMessageFromNotification() {
        Notification notification = Notification.pending(
                "ORDER_PLACED_EMAIL",
                "customer@example.com",
                "Order placed",
                "Your order has been placed.",
                UUID.randomUUID());

        sender.send(notification);

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage message = messageCaptor.getValue();

        assertThat(message.getTo()).containsExactly("customer@example.com");
        assertThat(message.getFrom()).isEqualTo("orders@example.com");
        assertThat(message.getSubject()).isEqualTo("Order placed");
        assertThat(message.getText()).isEqualTo("Your order has been placed.");
    }
}
