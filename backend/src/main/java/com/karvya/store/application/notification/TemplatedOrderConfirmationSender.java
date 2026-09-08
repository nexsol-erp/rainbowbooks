package com.karvya.store.application.notification;

import com.karvya.store.domain.model.EmailNotification;

/**
 * An alternate transport for the order-confirmation customer email, used by
 * {@link NotificationDispatcher} instead of the shared render-then-send path
 * when {@link #isConfigured()} says a provider is set up for it.
 *
 * <p>A port rather than a direct dependency on the one implementation
 * (MSG91's templated API, in infrastructure), for the same reason
 * {@link EmailSender} is one - and so a missing/blank configuration falls
 * back to the ordinary SMTP path rather than failing every order.
 */
public interface TemplatedOrderConfirmationSender {

    boolean isConfigured();

    /**
     * @throws EmailSender.EmailDeliveryException when the message could not
     *                                             be handed to the provider
     */
    void send(EmailNotification notification);
}
