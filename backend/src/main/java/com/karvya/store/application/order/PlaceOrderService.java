package com.karvya.store.application.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.karvya.store.application.cart.dto.CartDtos;
import com.karvya.store.application.identity.AddressService;
import com.karvya.store.application.identity.dto.AddressDtos;
import com.karvya.store.application.order.dto.CheckoutRequest;
import com.karvya.store.application.order.dto.OrderDtos;
import com.karvya.store.application.settings.SettingsService;
import com.karvya.store.domain.ConflictException;
import com.karvya.store.domain.NotFoundException;
import com.karvya.store.domain.model.*;
import com.karvya.store.domain.repository.*;
import com.karvya.store.infrastructure.config.AppProperties;
import com.karvya.store.infrastructure.security.SecureTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Turns a cart into an order. The transactional heart of the application.
 *
 * <p>Everything below happens in one transaction: product rows are locked,
 * stock is re-checked and decremented, line items are snapshotted, totals are
 * computed from the locked prices, and the order is written. Either all of it
 * commits or none of it does, so there is no state in which stock has moved
 * but no order exists, or an order exists whose stock was never taken.
 *
 * <p>Notifications are written to the outbox inside the same transaction and
 * sent afterwards by a worker. That is what lets an order survive an
 * unreachable mail server: the send is a separate, retryable concern.
 */
@Service
public class PlaceOrderService {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);
    private static final String CUSTOMER_ACTOR = "customer";

    private final ProductRepository products;
    private final CustomerOrderRepository orders;
    private final PaymentMethodRepository paymentMethods;
    private final AppUserRepository users;
    private final CustomerAddressRepository addresses;
    private final CartRepository carts;
    private final EmailNotificationRepository notifications;
    private final OrderNumberGenerator orderNumbers;
    private final SettingsService settings;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final OrderViewMapper viewMapper;
    private final AddressService addressService;

    public PlaceOrderService(ProductRepository products, CustomerOrderRepository orders,
                             PaymentMethodRepository paymentMethods, AppUserRepository users,
                             CustomerAddressRepository addresses, CartRepository carts,
                             EmailNotificationRepository notifications,
                             OrderNumberGenerator orderNumbers, SettingsService settings,
                             AppProperties properties, ObjectMapper objectMapper,
                             OrderViewMapper viewMapper, AddressService addressService) {
        this.products = products;
        this.orders = orders;
        this.paymentMethods = paymentMethods;
        this.users = users;
        this.addresses = addresses;
        this.carts = carts;
        this.notifications = notifications;
        this.orderNumbers = orderNumbers;
        this.settings = settings;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.viewMapper = viewMapper;
        this.addressService = addressService;
    }

    /**
     * Places an order.
     *
     * @param userId null for a guest checkout
     */
    @Transactional
    public OrderDtos.PlacedOrder place(CheckoutRequest request, Long userId) {
        Map<Long, Integer> wanted = mergeDuplicates(request.items());
        if (wanted.isEmpty()) {
            throw new CheckoutValidationException("Your cart is empty.", List.of());
        }

        PaymentMethod method = paymentMethods.findByCodeAndActiveTrue(request.paymentMethodCode())
                .orElseThrow(() -> new ConflictException("payment-method-unavailable",
                        "That payment method is not available. Please choose another."));

        // Locked in ascending id order. Two concurrent checkouts for the same
        // products therefore queue rather than deadlock, and neither can read
        // stock the other is about to take.
        List<Long> orderedIds = new ArrayList<>(wanted.keySet());
        Collections.sort(orderedIds);
        Map<Long, Product> locked = new LinkedHashMap<>();
        products.lockAllByIdInOrder(orderedIds).forEach(p -> locked.put(p.getId(), p));

        List<CartDtos.Adjustment> problems = verifyAvailability(wanted, locked);
        if (!problems.isEmpty()) {
            // nothing has been written yet, so the customer keeps their cart
            throw new CheckoutValidationException(
                    "Your cart changed while you were checking out.", problems);
        }

        // generated once, returned to the caller once, stored only as a hash
        String accessToken = SecureTokens.generate();

        CustomerOrder order = CustomerOrder.open(
                orderNumbers.next(),
                SecureTokens.hash(accessToken),
                method.getCode(),
                settings.getString(SettingsService.CURRENCY, "INR"));

        AppUser customer = (userId == null) ? null : users.findById(userId).orElse(null);
        if (customer != null) {
            order.setCustomer(customer);
        }

        applyDelivery(order, request, customer);
        rememberDeliveryAddress(request, customer);

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Long productId : orderedIds) {
            Product product = locked.get(productId);
            int quantity = wanted.get(productId);

            // the decrement happens under the lock taken above
            product.reserveStock(quantity);

            OrderItem line = order.addLine(product, quantity);
            subtotal = subtotal.add(line.getLineTotal());
        }

        order.setTotals(subtotal, deliveryChargeFor(subtotal));
        order.setUpdatedBy(CUSTOMER_ACTOR);
        order.recordPlacement(CUSTOMER_ACTOR);

        orders.saveAndFlush(order);

        queueNotifications(order, method);
        clearCartFor(userId);

        log.info("Order {} placed with {} lines, total {}",
                order.getOrderNumber(), order.getItems().size(), order.getTotal());

        return new OrderDtos.PlacedOrder(
                order.getOrderNumber(),
                accessToken,
                viewMapper.toDetail(order, method, order.getDeliveryEmail() != null));
    }

    // ---- validation -------------------------------------------------------

    /**
     * Checks every requested line against the locked rows. Collects all the
     * problems rather than failing on the first, so a customer is told
     * everything that changed at once instead of discovering it one item at a
     * time.
     */
    private List<CartDtos.Adjustment> verifyAvailability(Map<Long, Integer> wanted,
                                                         Map<Long, Product> locked) {
        List<CartDtos.Adjustment> problems = new ArrayList<>();

        for (Map.Entry<Long, Integer> entry : wanted.entrySet()) {
            Product product = locked.get(entry.getKey());
            int quantity = entry.getValue();

            if (product == null || product.getStatus() != ProductStatus.ACTIVE) {
                problems.add(new CartDtos.Adjustment(
                        entry.getKey(),
                        product == null ? "An item" : product.getName(),
                        CartDtos.Adjustment.Kind.REMOVED_UNAVAILABLE,
                        (product == null ? "An item in your cart" : product.getName())
                                + " is no longer available."));
                continue;
            }

            if (product.getStockQuantity() <= 0) {
                problems.add(new CartDtos.Adjustment(
                        product.getId(), product.getName(),
                        CartDtos.Adjustment.Kind.REMOVED_OUT_OF_STOCK,
                        product.getName() + " has just sold out."));
                continue;
            }

            if (product.getStockQuantity() < quantity) {
                problems.add(new CartDtos.Adjustment(
                        product.getId(), product.getName(),
                        CartDtos.Adjustment.Kind.QUANTITY_REDUCED,
                        "Only " + product.getStockQuantity() + " of " + product.getName()
                                + " remain, and you asked for " + quantity + "."));
            }
        }
        return problems;
    }

    private Map<Long, Integer> mergeDuplicates(List<CartDtos.LineRequest> items) {
        Map<Long, Integer> totals = new LinkedHashMap<>();
        for (CartDtos.LineRequest line : items) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                continue;
            }
            totals.merge(line.productId(), line.quantity(), Integer::sum);
        }
        return totals;
    }

    // ---- delivery ---------------------------------------------------------

    /**
     * Copies the delivery details onto the order.
     *
     * <p>A saved address is only honoured when it belongs to the signed-in
     * customer; otherwise the typed fields are used. That check is what stops
     * one customer addressing an order with another's stored address.
     */
    private void applyDelivery(CustomerOrder order, CheckoutRequest request, AppUser customer) {
        if (request.savedAddressId() != null && customer != null) {
            Optional<CustomerAddress> saved =
                    addresses.findByIdAndUserId(request.savedAddressId(), customer.getId());

            if (saved.isPresent()) {
                CustomerAddress address = saved.get();
                order.setDelivery(
                        address.getRecipientName(), address.getPhone(),
                        blankToNull(request.deliveryEmail()),
                        address.getLine1(), address.getLine2(), address.getCity(),
                        address.getState(), address.getPostalCode(),
                        blankToNull(request.deliveryNotes()),
                        blankToNull(request.customerComments()));
                return;
            }
            throw new NotFoundException("Address", String.valueOf(request.savedAddressId()));
        }

        order.setDelivery(
                request.deliveryName().trim(),
                request.deliveryPhone().trim(),
                blankToNull(request.deliveryEmail()),
                request.addressLine1().trim(),
                blankToNull(request.addressLine2()),
                request.city().trim(),
                request.state().trim(),
                request.postalCode().trim(),
                blankToNull(request.deliveryNotes()),
                blankToNull(request.customerComments()));
    }

/**
     * Keeps a signed-in customer's delivery address for next time.
     *
     * <p>Whichever address this order went to becomes the account default, so
     * the next checkout offers it already filled in. Guests are skipped: there
     * is no account to hang an address on.
     */
    private void rememberDeliveryAddress(CheckoutRequest request, AppUser customer) {
        if (customer == null) {
            return;
        }

        if (request.savedAddressId() != null) {
            addressService.promoteToDefault(customer.getId(), request.savedAddressId());
            return;
        }

        addressService.rememberFromCheckout(customer.getId(), new AddressDtos.Request(
                null,
                request.deliveryName().trim(),
                request.deliveryPhone().trim(),
                request.addressLine1().trim(),
                blankToNull(request.addressLine2()),
                request.city().trim(),
                request.state().trim(),
                request.postalCode().trim(),
                true));
    }

    private BigDecimal deliveryChargeFor(BigDecimal subtotal) {
        BigDecimal charge = settings.getMoney(SettingsService.DELIVERY_CHARGE, BigDecimal.ZERO);
        return settings.getOptionalMoney(SettingsService.FREE_DELIVERY_THRESHOLD)
                .filter(threshold -> subtotal.compareTo(threshold) >= 0)
                .map(threshold -> BigDecimal.ZERO)
                .orElse(charge);
    }

    // ---- side effects -----------------------------------------------------

    /**
     * Writes the notifications into the outbox. Inside the transaction, so
     * they commit with the order; sent outside it, so a broken mail server
     * cannot roll back a sale.
     */
    private void queueNotifications(CustomerOrder order, PaymentMethod method) {
        String adminEmail = settings.find(SettingsService.ADMIN_EMAIL)
                .orElse(properties.adminNotificationEmail());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNumber", order.getOrderNumber());
        payload.put("customerName", order.getDeliveryName());
        payload.put("total", order.getTotal().toPlainString());
        payload.put("currency", order.getCurrency());
        payload.put("itemCount", order.getItems().stream().mapToInt(OrderItem::getQuantity).sum());
        payload.put("paymentMethod", method.getLabel());
        payload.put("paymentInstructions", method.getInstructions());
        // Only MSG91's order-confirmation template variables want this; the
        // Thymeleaf templates list items themselves and ignore it.
        payload.put("productName", order.getItems().stream()
                .map(OrderItem::getProductName)
                .collect(Collectors.joining(", ")));

        if (adminEmail != null && !adminEmail.isBlank()) {
            notifications.save(withOrder(EmailNotification.queue(
                    EmailNotification.TYPE_ORDER_ADMIN, adminEmail,
                    "New order " + order.getOrderNumber(), json(payload)), order));
        }

        if (order.getDeliveryEmail() != null && !order.getDeliveryEmail().isBlank()) {
            notifications.save(withOrder(EmailNotification.queue(
                    EmailNotification.TYPE_ORDER_CUSTOMER, order.getDeliveryEmail(),
                    "Your order " + order.getOrderNumber(), json(payload)), order));
        }
    }

    private EmailNotification withOrder(EmailNotification notification, CustomerOrder order) {
        notification.setRelatedOrderId(order.getId());
        return notification;
    }

    /** A signed-in customer's cart is emptied only once the order has committed. */
    private void clearCartFor(Long userId) {
        if (userId == null) {
            return;
        }
        carts.findByUserId(userId).ifPresent(cart -> {
            cart.clear();
            carts.save(cart);
        });
    }

    // ---- plumbing ---------------------------------------------------------

    private String json(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the notification payload", e);
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
