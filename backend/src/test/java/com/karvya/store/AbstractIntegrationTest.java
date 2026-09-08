package com.karvya.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.karvya.store.domain.model.Product;
import com.karvya.store.domain.repository.CategoryRepository;
import com.karvya.store.domain.repository.EmailNotificationRepository;
import com.karvya.store.domain.repository.ProductRepository;
import com.karvya.store.infrastructure.security.RateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;

/**
 * Base for tests that need the real thing.
 *
 * <p>A genuine PostgreSQL container rather than an in-memory substitute,
 * because most of what is worth testing here is database behaviour H2 does not
 * reproduce: partial unique indexes, {@code FOR UPDATE} semantics, JSONB, and
 * the untyped-null-parameter trap that broke catalogue search.
 *
 * <p>The container is started once from a static initialiser and deliberately
 * never stopped. The obvious alternative - {@code @Testcontainers} with
 * {@code @Container} - manages the lifecycle <em>per test class</em>, so
 * declaring it on a shared base tears the database down after the first class
 * finishes and every later class fails with "connection refused". Starting it
 * here instead leaves cleanup to Testcontainers' own reaper at JVM exit, which
 * is what makes it genuinely shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.5-alpine")
                    .withDatabaseName("karvya_test")
                    .withUsername("karvya")
                    .withPassword("karvya");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private EmailNotificationRepository emailNotificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * Clears the throttle between tests. Every test drives the same loopback
     * address, so the per-IP allowance would otherwise be shared across the
     * whole suite and later tests would fail on a limit earlier ones consumed.
     */
    @BeforeEach
    void clearRateLimits() {
        rateLimiter.evictExpired(java.time.Duration.ZERO);
    }

    /**
     * The stock levels V3__seed_catalogue.sql establishes.
     *
     * <p>Anything that places an order permanently consumes stock from a
     * database shared by the whole suite, so a class that buys things silently
     * changes what a later class sees. Restoring after every test removes that
     * coupling entirely rather than asking each class to remember.
     */
    private static final Map<String, Integer> SEEDED_STOCK = Map.of(
            "KV-BH-01", 8,
            "KV-BH-02", 6,
            "KV-BH-03", 4,
            "KV-BH-04", 2,
            "KV-BH-05", 5);

    /** The one category V2__reference_data.sql establishes. */
    private static final String SEEDED_CATEGORY_SLUG = "bird-houses-and-nests";

    /**
     * Puts the seeded data back after every test.
     *
     * <p>Stock, products and categories all leak between classes otherwise: an
     * order permanently consumes stock, and a product a test creates (e.g. to
     * prove a second category of thing can be sold) changes the counts another
     * class asserts on. Restoring centrally removes the coupling rather than
     * asking every class to remember.
     *
     * <p>Products are deleted before categories so a category a test created
     * to hold one is empty by the time the category pass runs, rather than
     * surviving because something still (transiently) referenced it.
     */
    @AfterEach
    void restoreSeededData() {
        transactionTemplate.executeWithoutResult(status -> {
            SEEDED_STOCK.forEach((sku, seeded) -> productRepository.findBySku(sku)
                    .filter(product -> product.getStockQuantity() != seeded)
                    .ifPresent(product -> {
                        product.setStockQuantity(seeded);
                        productRepository.save(product);
                    }));

            productRepository.findAll().stream()
                    .filter(product -> !SEEDED_STOCK.containsKey(product.getSku()))
                    .forEach(productRepository::delete);

            categoryRepository.findAll().stream()
                    .filter(category -> !SEEDED_CATEGORY_SLUG.equals(category.getSlug()))
                    .filter(category -> productRepository
                            .findAll(com.karvya.store.domain.repository.ProductSpecifications
                                    .inCategory(category.getSlug()))
                            .isEmpty())
                    .forEach(categoryRepository::delete);

            // The outbox is global: the dispatcher claims whatever is due,
            // regardless of which test enqueued it. A failed send is not lost,
            // it is scheduled for retry - so notifications a previous test left
            // PENDING become due again once their backoff elapses, and the next
            // drain delivers them alongside its own. That makes any assertion
            // on "how many went out" depend on how long the suite has been
            // running, which is why it passes locally and fails on a slower
            // machine.
            emailNotificationRepository.deleteAll();
        });
    }

    /**
     * A unique email address for a test fixture.
     *
     * <p>One counter for the whole suite. Per-class counters look fine in
     * isolation and then collide: two classes each starting at 1 both produce
     * shopper1@example.com, and whichever runs second fails on a duplicate.
     */
    private static final java.util.concurrent.atomic.AtomicLong EMAIL_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    protected String uniqueEmail(String prefix) {
        return prefix + EMAIL_SEQ.incrementAndGet() + "@example.test";
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
