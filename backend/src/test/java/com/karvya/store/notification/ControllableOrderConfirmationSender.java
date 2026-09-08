package com.karvya.store.notification;

import com.karvya.store.application.notification.TemplatedOrderConfirmationSender;
import com.karvya.store.domain.model.EmailNotification;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A stand-in for MSG91's templated API, the same way {@link ControllableEmailSender}
 * stands in for SMTP - starts unconfigured, so a test has to opt in before
 * {@link com.karvya.store.application.notification.NotificationDispatcher}
 * will route anything to it.
 */
public class ControllableOrderConfirmationSender implements TemplatedOrderConfirmationSender {

    private final AtomicBoolean configured = new AtomicBoolean(false);
    private final List<Long> sentNotificationIds = Collections.synchronizedList(new ArrayList<>());

    public void enable() {
        configured.set(true);
    }

    public void reset() {
        configured.set(false);
        sentNotificationIds.clear();
    }

    public List<Long> sentNotificationIds() {
        return List.copyOf(sentNotificationIds);
    }

    @Override
    public boolean isConfigured() {
        return configured.get();
    }

    @Override
    public void send(EmailNotification notification) {
        sentNotificationIds.add(notification.getId());
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public ControllableOrderConfirmationSender controllableOrderConfirmationSender() {
            return new ControllableOrderConfirmationSender();
        }
    }
}
