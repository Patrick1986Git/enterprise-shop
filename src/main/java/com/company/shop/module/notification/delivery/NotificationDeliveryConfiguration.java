package com.company.shop.module.notification.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationSmtpProperties.class, NotificationDeliveryProperties.class})
public class NotificationDeliveryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.notification.smtp", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(NotificationSender.class)
    SmtpNotificationSender smtpNotificationSender(JavaMailSender mailSender, NotificationSmtpProperties properties,
            NotificationDeliveryProperties deliveryProperties) {
        configureTimeouts(mailSender, properties, deliveryProperties);
        return new SmtpNotificationSender(mailSender, properties);
    }

    private void configureTimeouts(JavaMailSender mailSender, NotificationSmtpProperties smtpProperties,
            NotificationDeliveryProperties deliveryProperties) {
        if (!(mailSender instanceof JavaMailSenderImpl javaMailSender)) {
            throw new IllegalStateException("SMTP notification delivery requires JavaMailSenderImpl for bounded timeouts");
        }
        requireShorterThanClaim(smtpProperties.connectionTimeout(), deliveryProperties.claimDuration(), "connectionTimeout");
        requireShorterThanClaim(smtpProperties.readTimeout(), deliveryProperties.claimDuration(), "readTimeout");
        requireShorterThanClaim(smtpProperties.writeTimeout(), deliveryProperties.claimDuration(), "writeTimeout");
        javaMailSender.getJavaMailProperties().setProperty(
                "mail.smtp.connectiontimeout", Long.toString(smtpProperties.connectionTimeout().toMillis()));
        javaMailSender.getJavaMailProperties().setProperty(
                "mail.smtp.timeout", Long.toString(smtpProperties.readTimeout().toMillis()));
        javaMailSender.getJavaMailProperties().setProperty(
                "mail.smtp.writetimeout", Long.toString(smtpProperties.writeTimeout().toMillis()));
    }

    private void requireShorterThanClaim(java.time.Duration timeout, java.time.Duration claimDuration, String name) {
        if (timeout.compareTo(claimDuration) >= 0) {
            throw new IllegalStateException("SMTP " + name + " must be shorter than notification claimDuration");
        }
    }

    @Bean
    @ConditionalOnMissingBean(NotificationSender.class)
    NoopNotificationSender noopNotificationSender() {
        return new NoopNotificationSender();
    }
}
