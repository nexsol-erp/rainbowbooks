package com.karvya.store.application.notification;

import com.karvya.store.domain.model.EmailNotification;
import com.karvya.store.domain.model.NotificationStatus;
import com.karvya.store.domain.repository.EmailNotificationRepository;
import com.karvya.store.infrastructure.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The transactional half of outbox delivery.
 *
 * <p>A separate bean from {@link NotificationWorker} on purpose. Calling a
 * {@code @Transactional} method from another method of the same class bypasses
 * the Spring proxy entirely, so claim and record would silently run with no
 * transaction at all - the row lease would never commit and every notification
 * would be sent repeatedly.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    /**
     * How long a claimed row is reserved. Long enough for a slow send plus its
     * timeout, short enough that a worker killed mid-send releases the row in
     * minutes rather than stranding it.
     */
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final EmailNotificationRepository notifications;
    private final EmailSender emailSender;
    private final EmailRenderer renderer;
    private final AppProperties properties;
    private final TemplatedOrderConfirmationSender orderConfirmationSender;

    public NotificationDispatcher(EmailNotificationRepository notifications, EmailSender emailSender,
                                  EmailRenderer renderer, AppProperties properties,
                                  TemplatedOrderConfirmationSender orderConfirmationSender) {
        this.notifications = notifications;
        this.emailSender = emailSender;
        this.renderer = renderer;
        this.properties = properties;
        this.orderConfirmationSender = orderConfirmationSender;
    }

    /**
     * Reserves a batch of due notifications and returns their ids. Commits
     * before any sending starts, so rows are leased rather than locked across
     * network I/O.
     */
    @Transactional
    public List<Long> claimBatch() {
        List<EmailNotification> due = notifications.claimDue(
                Instant.now(), PageRequest.of(0, properties.notifications().batchSize()));

        Instant leaseExpiry = Instant.now().plus(LEASE);
        due.forEach(notification -> notification.reserveUntil(leaseExpiry));
        return due.stream().map(EmailNotification::getId).toList();
    }

    /**
     * Sends one notification and records what happened.
     *
     * <p>{@code REQUIRES_NEW} so each row commits on its own: a mail server
     * that rejects one address must not undo sends that already succeeded.
     *
     * @return true when it was delivered
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attempt(Long id) {
        EmailNotification notification = notifications.findById(id).orElse(null);
        if (notification == null || notification.getStatus() != NotificationStatus.PENDING) {
            return false;
        }

        try {
            // MSG91's templated API renders the customer confirmation itself,
            // so nothing is rendered here for that one type - not a general
            // replacement for SMTP, just this one notification.
            if (EmailNotification.TYPE_ORDER_CUSTOMER.equals(notification.getType())
                    && orderConfirmationSender.isConfigured()) {
                orderConfirmationSender.send(notification);
            } else {
                String body = renderer.render(notification);
                emailSender.send(notification.getRecipient(), notification.getSubject(), body);
            }
            notification.markSent();
            return true;

        } catch (Exception e) {
            notification.markAttemptFailed(e.getMessage(), properties.notifications().maxAttempts());

            if (notification.getStatus() == NotificationStatus.FAILED) {
                // out of retries: this one needs a person, and the admin
                // dashboard surfaces it
                log.error("Notification {} to {} failed permanently after {} attempts: {}",
                        id, notification.getRecipient(), notification.getAttempts(), e.getMessage());
            } else {
                log.warn("Notification {} to {} failed (attempt {}), next try at {}",
                        id, notification.getRecipient(), notification.getAttempts(),
                        notification.getNextAttemptAt());
            }
            return false;
        }
    }
}
