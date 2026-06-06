package com.company.shop.module.notification.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationSmtpProperties.class)
public class NotificationDeliveryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.notification.smtp", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(NotificationSender.class)
    SmtpNotificationSender smtpNotificationSender(JavaMailSender mailSender, NotificationSmtpProperties properties) {
        return new SmtpNotificationSender(mailSender, properties);
    }

    @Bean
    @ConditionalOnMissingBean(NotificationSender.class)
    NoopNotificationSender noopNotificationSender() {
        return new NoopNotificationSender();
    }
}
