package com.karvya.store.application.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karvya.store.application.settings.SettingsService;
import com.karvya.store.domain.model.EmailNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns a queued notification into an HTML body.
 *
 * <p>The payload is whatever was stored as JSONB when the notification was
 * queued, so a template only ever sees the data as it was at that moment. That
 * matters: an order confirmation sent an hour late should describe the order
 * as it was placed, not as it stands now.
 */
@Component
public class EmailRenderer {

    private static final Logger log = LoggerFactory.getLogger(EmailRenderer.class);

    private final TemplateEngine templateEngine;
    private final ObjectMapper objectMapper;
    private final SettingsService settings;

    public EmailRenderer(TemplateEngine templateEngine, ObjectMapper objectMapper,
                         SettingsService settings) {
        this.templateEngine = templateEngine;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    public String render(EmailNotification notification) {
        Context context = new Context();
        context.setVariables(readPayload(notification));
        context.setVariable("storeName", settings.getString(SettingsService.STORE_NAME, "Rainbow Books"));
        context.setVariable("subject", notification.getSubject());

        return templateEngine.process(templateFor(notification.getType()), context);
    }

    private String templateFor(String type) {
        return switch (type) {
            case EmailNotification.TYPE_ORDER_ADMIN -> "email/order-admin";
            case EmailNotification.TYPE_ORDER_CUSTOMER -> "email/order-customer";
            case EmailNotification.TYPE_PASSWORD_RESET -> "email/password-reset";
            case EmailNotification.TYPE_ENQUIRY_ADMIN -> "email/enquiry-admin";
            default -> throw new IllegalArgumentException("No template for notification type " + type);
        };
    }

    private Map<String, Object> readPayload(EmailNotification notification) {
        try {
            return objectMapper.readValue(notification.getPayload(), new TypeReference<>() {
            });
        } catch (Exception e) {
            // a malformed payload must not wedge the whole queue; the send will
            // fail, be retried, and eventually be marked FAILED for a human
            log.warn("Notification {} has an unreadable payload", notification.getId(), e);
            return new HashMap<>();
        }
    }
}
