package com.karvya.store.infrastructure.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karvya.store.application.notification.EmailSender;
import com.karvya.store.application.notification.TemplatedOrderConfirmationSender;
import com.karvya.store.domain.model.EmailNotification;
import com.karvya.store.infrastructure.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Sends the order-confirmation customer email through MSG91's templated
 * email API, instead of the SMTP path everything else uses.
 *
 * <p>MSG91 renders the email itself from a template it already holds - this
 * only ever sends the three variables the template was built around. It is
 * not a general-purpose mail transport: nothing else routes through here.
 */
@Component
public class Msg91OrderConfirmationSender implements TemplatedOrderConfirmationSender {

    private static final Logger log = LoggerFactory.getLogger(Msg91OrderConfirmationSender.class);
    private static final String ENDPOINT = "https://control.msg91.com/api/v5/email/send";

    private final RestClient restClient;
    private final AppProperties.Msg91 config;
    private final ObjectMapper objectMapper;

    public Msg91OrderConfirmationSender(AppProperties properties, ObjectMapper objectMapper) {
        this.config = properties.msg91();
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(ENDPOINT)
                .defaultHeader("authkey", config.authKey() == null ? "" : config.authKey())
                .build();
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public void send(EmailNotification notification) {
        Map<String, Object> payload = readPayload(notification);
        String customerName = String.valueOf(payload.getOrDefault("customerName", notification.getRecipient()));

        Map<String, Object> body = Map.of(
                "recipients", List.of(Map.of(
                        "to", List.of(Map.of("email", notification.getRecipient(), "name", customerName)),
                        "variables", Map.of(
                                "order_id", String.valueOf(payload.getOrDefault("orderNumber", "")),
                                "product_name", String.valueOf(payload.getOrDefault("productName", "")),
                                "amount", String.valueOf(payload.getOrDefault("total", ""))
                        )
                )),
                "from", Map.of("email", config.fromEmail()),
                "domain", config.domain(),
                "template_id", config.orderConfirmationTemplateId()
        );

        try {
            String response = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.debug("MSG91 accepted order confirmation to {}: {}", notification.getRecipient(), response);

        } catch (RestClientException e) {
            // the recipient is logged, the body is not - matches SmtpEmailSender
            throw new EmailSender.EmailDeliveryException(
                    "Could not deliver order confirmation to " + notification.getRecipient() + " via MSG91", e);
        }
    }

    private Map<String, Object> readPayload(EmailNotification notification) {
        try {
            return objectMapper.readValue(notification.getPayload(), new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Notification {} has an unreadable payload", notification.getId(), e);
            return Map.of();
        }
    }
}
