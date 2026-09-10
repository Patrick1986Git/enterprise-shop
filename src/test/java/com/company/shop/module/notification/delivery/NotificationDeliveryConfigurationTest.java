package com.company.shop.module.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.company.shop.module.notification.entity.Notification;

class NotificationDeliveryConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(NotificationDeliveryConfiguration.class);

    @Test
    void configuration_shouldRegisterNoopSenderByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NotificationSender.class);
            assertThat(context).hasSingleBean(NoopNotificationSender.class);
            assertThat(context).doesNotHaveBean(SmtpNotificationSender.class);
            assertThat(context).doesNotHaveBean(JavaMailSender.class);
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
                    assertThat(context).doesNotHaveBean(JavaMailSender.class);
                    assertThat(context.getBean(NotificationSender.class)).isInstanceOf(NoopNotificationSender.class);
                });
    }

    @Test
    void configuration_shouldFailClosedWhenSmtpEnabledWithoutMailHost() {
        contextRunner
                .withPropertyValues(
                        "app.notification.smtp.enabled=true",
                        "spring.mail.password=diagnostic-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "SMTP notification delivery is enabled but spring.mail.host is missing or blank");
                    assertThat(stackTrace(context.getStartupFailure()))
                            .doesNotContain("diagnostic-secret", "ConnectException");
                });
    }

    @Test
    void configuration_shouldRegisterSmtpSenderWhenSmtpEnabledWithMailHost() {
        contextRunner
                .withPropertyValues(
                        "app.notification.smtp.enabled=true",
                        "spring.mail.host=unresolvable.invalid")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationSender.class);
                    assertThat(context).hasSingleBean(SmtpNotificationSender.class);
                    assertThat(context).hasSingleBean(JavaMailSender.class);
                    assertThat(context).doesNotHaveBean(NoopNotificationSender.class);
                    assertThat(context.getBean(NotificationSender.class)).isInstanceOf(SmtpNotificationSender.class);
                    JavaMailSenderImpl mailSender = context.getBean(JavaMailSenderImpl.class);
                    assertThat(mailSender.getHost()).isEqualTo("unresolvable.invalid");
                    assertThat(mailSender.getProtocol()).isEqualTo("smtp");
                    assertThat(mailSender.getPort()).isEqualTo(JavaMailSenderImpl.DEFAULT_PORT);
                    assertThat(mailSender.getUsername()).isNull();
                    assertThat(mailSender.getPassword()).isNull();
                    assertThat(mailSender.getSession().getProperty("mail.smtp.connectiontimeout")).isEqualTo("30000");
                    assertThat(mailSender.getSession().getProperty("mail.smtp.timeout")).isEqualTo("30000");
                    assertThat(mailSender.getSession().getProperty("mail.smtp.writetimeout")).isEqualTo("30000");
                    assertThat(mailSender.getSession().getProperty("mail.smtp.auth")).isNull();
                    assertThat(mailSender.getSession().getProperty("mail.smtp.starttls.enable")).isNull();
                    assertThat(mailSender.getSession().getProperty("mail.smtp.ssl.enable")).isNull();
                });
    }

    @Test
    void configuration_shouldApplyTimeoutOverridesToEffectiveJavaMailSession() {
        contextRunner
                .withPropertyValues(
                        "app.notification.smtp.enabled=true",
                        "spring.mail.host=unresolvable.invalid",
                        "app.notification.smtp.connection-timeout=PT5S",
                        "app.notification.smtp.read-timeout=PT6S",
                        "app.notification.smtp.write-timeout=PT7S")
                .run(context -> {
                    JavaMailSenderImpl mailSender = context.getBean(JavaMailSenderImpl.class);
                    assertThat(mailSender.getSession().getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
                    assertThat(mailSender.getSession().getProperty("mail.smtp.timeout")).isEqualTo("6000");
                    assertThat(mailSender.getSession().getProperty("mail.smtp.writetimeout")).isEqualTo("7000");
                });
    }

    @Test
    void configuration_shouldFailWhenTimeoutIsNotShorterThanClaimLease() {
        contextRunner
                .withPropertyValues(
                        "app.notification.smtp.enabled=true",
                        "spring.mail.host=unresolvable.invalid",
                        "app.notification.smtp.read-timeout=PT5M")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void configuration_shouldNotConnectToMailTransportDuringStartup() {
        RecordingJavaMailSender mailSender = new RecordingJavaMailSender();

        contextRunner
                .withBean(RecordingJavaMailSender.class, () -> mailSender)
                .withPropertyValues(
                        "app.notification.smtp.enabled=true",
                        "spring.mail.host=smtp.example.invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SmtpNotificationSender.class);
                    assertThat(mailSender.connectionTested).isFalse();
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

    private static String stackTrace(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    private static final class RecordingJavaMailSender extends JavaMailSenderImpl {

        private boolean connectionTested;

        @Override
        public void testConnection() {
            connectionTested = true;
        }
    }
}
