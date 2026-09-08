package com.karvya.store.notification;

import com.karvya.store.AbstractIntegrationTest;
import com.karvya.store.application.notification.NotificationDispatcher;
import com.karvya.store.domain.model.EmailNotification;
import com.karvya.store.domain.model.NotificationStatus;
import com.karvya.store.domain.repository.CustomerOrderRepository;
import com.karvya.store.domain.repository.EmailNotificationRepository;
import com.karvya.store.domain.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox: delivery, retry, and the guarantee the whole design exists for -
 * that an order is never lost because email failed.
 *
 * <p>The scheduled worker is disabled in the test profile, so each pass here is
 * driven explicitly rather than racing a timer.
 */
@Import({ControllableEmailSender.Config.class, ControllableOrderConfirmationSender.Config.class})
class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ControllableEmailSender emailSender;
    @Autowired private ControllableOrderConfirmationSender orderConfirmationSender;
    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private EmailNotificationRepository notifications;
    @Autowired private CustomerOrderRepository orders;
    @Autowired private ProductRepository products;

    @BeforeEach
    void resetSender() {
        emailSender.reset();
        orderConfirmationSender.reset();

        // Every drain here claims the whole outbox, so these tests are only
        // meaningful starting from an empty one. Stated as an assertion rather
        // than assumed: a leak shows up as this line failing, instead of as a
        // count that is wrong by however many messages happened to be due.
        assertThat(notifications.count()).isZero();
    }

    /** Runs one full pass: claim, send, record. */
    private int drainOutbox() {
        List<Long> claimed = dispatcher.claimBatch();
        int sent = 0;
        for (Long id : claimed) {
            if (dispatcher.attempt(id)) sent++;
        }
        return sent;
    }

    private String placeOrder(String customerEmail) throws Exception {
        Long productId = products.findBySku("KV-BH-01").orElseThrow().getId();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(Map.of("productId", productId, "quantity", 1)));
        body.put("deliveryName", "Asha Menon");
        body.put("deliveryPhone", "9876543210");
        if (customerEmail != null) body.put("deliveryEmail", customerEmail);
        body.put("addressLine1", "1 Coir Lane");
        body.put("city", "Kochi");
        body.put("state", "Kerala");
        body.put("postalCode", "682001");
        body.put("paymentMethodCode", "CASH_ON_DELIVERY");

        MvcResult result = mockMvc.perform(post("/api/v1/orders").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("orderNumber").asText();
    }

    private List<EmailNotification> notificationsFor(String orderNumber) {
        return notifications.findAll().stream()
                .filter(n -> n.getPayload() != null && n.getPayload().contains(orderNumber))
                .toList();
    }

    // ---- the guarantee ----------------------------------------------------

    /**
     * The point of the outbox, stated as a test: SMTP is down for the whole
     * exchange, and the order still exists, still holds its stock, and its
     * notifications are queued for another try.
     */
    @Test
    @DisplayName("an order survives the mail server being down")
    void orderSurvivesMailServerOutage() throws Exception {
        emailSender.failEverything();

        String orderNumber = placeOrder("asha@example.com");

        // the order committed even though nothing can be delivered
        assertThat(orders.findByOrderNumber(orderNumber)).isPresent();

        int sent = drainOutbox();
        assertThat(sent).isZero();
        assertThat(emailSender.sent()).isEmpty();

        // still there, still the customer's, still going to be retried
        assertThat(orders.findByOrderNumber(orderNumber)).isPresent();

        List<EmailNotification> queued = notificationsFor(orderNumber);
        assertThat(queued).hasSize(2);
        assertThat(queued).allSatisfy(notification -> {
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
            assertThat(notification.getAttempts()).isEqualTo(1);
            assertThat(notification.getLastError()).isNotBlank();
            assertThat(notification.getNextAttemptAt()).isAfter(Instant.now());
        });
    }

    @Test
    @DisplayName("once the mail server returns, the queued messages go out")
    void deliversOnceTheServerRecovers() throws Exception {
        emailSender.failEverything();
        String orderNumber = placeOrder("asha@example.com");
        drainOutbox();

        assertThat(notificationsFor(orderNumber))
                .allMatch(n -> n.getStatus() == NotificationStatus.PENDING);

        // backoff has pushed them into the future, so make them due again
        transactionalMakeDue(orderNumber);
        emailSender.recover();

        drainOutbox();

        // asserted against this order rather than the drain's total: the outbox
        // is shared, so a global count answers "what went out just now", not
        // "did this order's mail go out"
        assertThat(notificationsFor(orderNumber))
                .hasSize(2)
                .allMatch(n -> n.getStatus() == NotificationStatus.SENT);
    }

    // ---- retry policy -----------------------------------------------------

    @Test
    @DisplayName("each failure pushes the next attempt further out")
    void backsOffBetweenAttempts() throws Exception {
        emailSender.failEverything();
        String orderNumber = placeOrder(null);

        drainOutbox();
        Instant afterFirst = notificationsFor(orderNumber).get(0).getNextAttemptAt();

        transactionalMakeDue(orderNumber);
        drainOutbox();
        Instant afterSecond = notificationsFor(orderNumber).get(0).getNextAttemptAt();

        assertThat(notificationsFor(orderNumber).get(0).getAttempts()).isEqualTo(2);
        assertThat(afterSecond)
                .as("the second retry should be scheduled later than the first")
                .isAfter(afterFirst);
    }

    /**
     * The retry budget is finite. A permanently bad address must stop being
     * retried and become visible to a person instead of looping forever.
     */
    @Test
    @DisplayName("a notification gives up after the configured number of attempts")
    void stopsRetryingEventually() throws Exception {
        emailSender.failEverything();
        String orderNumber = placeOrder(null);

        // the test profile allows three attempts
        for (int pass = 0; pass < 3; pass++) {
            transactionalMakeDue(orderNumber);
            drainOutbox();
        }

        List<EmailNotification> queued = notificationsFor(orderNumber);
        assertThat(queued).allSatisfy(notification -> {
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
            assertThat(notification.getAttempts()).isEqualTo(3);
        });

        // and a failed notification is not picked up again
        transactionalMakeDue(orderNumber);
        assertThat(drainOutbox()).isZero();
    }

    // ---- templated sender ---------------------------------------------------

    /**
     * The point of routing by type rather than always trying the templated
     * sender first: an unconfigured provider must not silently swallow every
     * customer confirmation.
     */
    @Test
    @DisplayName("the customer confirmation uses the templated sender once one is configured")
    void usesTemplatedSenderWhenConfigured() throws Exception {
        orderConfirmationSender.enable();

        String orderNumber = placeOrder("asha@example.com");
        drainOutbox();

        EmailNotification customerNotification = notificationsFor(orderNumber).stream()
                .filter(n -> n.getType().equals(EmailNotification.TYPE_ORDER_CUSTOMER))
                .findFirst().orElseThrow();

        assertThat(orderConfirmationSender.sentNotificationIds()).contains(customerNotification.getId());
        assertThat(customerNotification.getStatus()).isEqualTo(NotificationStatus.SENT);

        // the admin alert is a different type, so it is unaffected
        assertThat(emailSender.sent()).anyMatch(m -> m.subject().contains(orderNumber));
        // and the templated sender took the customer's copy, not SMTP
        assertThat(emailSender.sent()).noneMatch(m -> m.to().equals("asha@example.com"));
    }

    // ---- claiming ---------------------------------------------------------

    @Test
    @DisplayName("claiming leases a row so a second pass does not send it twice")
    void claimingLeasesRows() throws Exception {
        placeOrder("asha@example.com");

        List<Long> first = dispatcher.claimBatch();
        assertThat(first).isNotEmpty();

        // the lease pushed them out of the due window
        List<Long> second = dispatcher.claimBatch();
        assertThat(second).doesNotContainAnyElementsOf(first);
    }

    // ---- rendering --------------------------------------------------------

    @Test
    @DisplayName("the customer email carries the order number and payment method")
    void rendersTheOrderIntoTheBody() throws Exception {
        String orderNumber = placeOrder("asha@example.com");
        drainOutbox();

        var customerMessage = emailSender.sent().stream()
                .filter(m -> m.to().equals("asha@example.com"))
                .findFirst()
                .orElseThrow();

        assertThat(customerMessage.subject()).contains(orderNumber);
        assertThat(customerMessage.body())
                .contains(orderNumber)
                .contains("Cash on delivery")
                .contains("Payment instructions will be")
                // rendered, not left as raw template expressions
                .doesNotContain("th:text");
    }

    @Test
    @DisplayName("the administrator alert names the customer and the total")
    void rendersTheAdminAlert() throws Exception {
        String orderNumber = placeOrder(null);
        drainOutbox();

        var adminMessage = emailSender.sent().stream()
                .filter(m -> m.subject().contains(orderNumber))
                .findFirst()
                .orElseThrow();

        assertThat(adminMessage.body())
                .contains(orderNumber)
                .contains("Asha Menon")
                .contains("New order received");
    }

    /** Resets backoff so the next pass sees the rows as due. */
    private void transactionalMakeDue(String orderNumber) {
        notificationsFor(orderNumber).forEach(notification -> {
            notification.reserveUntil(Instant.now().minusSeconds(1));
            notifications.save(notification);
        });
        notifications.flush();
    }
}
