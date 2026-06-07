package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;

import com.company.shop.module.notification.entity.Notification;

class NotificationDeliveryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationDeliveryConfiguration.class);

    @Test
    void configuration_shouldRegisterNoopSenderByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NotificationSender.class);
            assertThat(context).hasSingleBean(NoopNotificationSender.class);
            assertThat(context).doesNotHaveBean(SmtpNotificationSender.class);
            assertThat(context.getBean(NotificationSender.class)).isInstanceOf(NoopNotificationSender.class);
        });
    }

    @Test
    void configuration_shouldRegisterNoopSenderWhenSmtpDisabled() {
        contextRunner
                .withPropertyValues("app.notification.smtp.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationSender.class);
                    assertThat(context).hasSingleBean(NoopNotificationSender.class);
                    assertThat(context).doesNotHaveBean(SmtpNotificationSender.class);
                    assertThat(context.getBean(NotificationSender.class)).isInstanceOf(NoopNotificationSender.class);
                });
    }

    @Test
    void configuration_shouldRegisterSmtpSenderWhenSmtpEnabled() {
        contextRunner
                .withUserConfiguration(JavaMailSenderConfiguration.class)
                .withPropertyValues("app.notification.smtp.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationSender.class);
                    assertThat(context).hasSingleBean(SmtpNotificationSender.class);
                    assertThat(context).doesNotHaveBean(NoopNotificationSender.class);
                    assertThat(context.getBean(NotificationSender.class)).isInstanceOf(SmtpNotificationSender.class);
                });
    }

    @Test
    void configuration_shouldUseCustomSenderWhenNotificationSenderExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomSenderConfiguration.class, NotificationDeliveryConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationSender.class);
                    assertThat(context.getBean(NotificationSender.class)).isInstanceOf(CustomNotificationSender.class);
                    assertThat(context).doesNotHaveBean(NoopNotificationSender.class);
                    assertThat(context).doesNotHaveBean(SmtpNotificationSender.class);
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JavaMailSenderConfiguration {

        @Bean
        JavaMailSender javaMailSender() {
            return mock(JavaMailSender.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CustomSenderConfiguration {

        @Bean
        NotificationSender customNotificationSender() {
            return new CustomNotificationSender();
        }
    }

    private static final class CustomNotificationSender implements NotificationSender {

        @Override
        public void send(Notification notification) {
        }
    }
}
