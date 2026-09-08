package com.karvya.store.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application settings bound from {@code app.*}.
 *
 * <p>Everything environment-specific arrives here rather than being read
 * ad hoc, so there is one place to look for what a deployment can change and
 * nothing has a secret baked into a default.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        String storageDir,
        String mailFrom,
        String adminNotificationEmail,
        /** Fallback when no number has been set through the admin. */
        String whatsAppNumber,
        BootstrapAdmin bootstrapAdmin,
        Security security,
        Notifications notifications,
        Msg91 msg91
) {

    /**
     * The first administrator, created on first boot only. The password is
     * supplied through the environment and the account is forced to change it
     * at first login, so the bootstrap value never remains valid for long.
     */
    public record BootstrapAdmin(String username, String email, String password) {
        public boolean isConfigured() {
            return password != null && !password.isBlank();
        }
    }

    /**
     * Throttles were constants inside the controllers, which made them
     * unrunnable against end-to-end tests: five registrations per hour is right
     * for production and stops a test suite dead on its sixth run. They belong
     * in configuration so a deployment can set them and a test environment can
     * relax them, without either editing code.
     */
    public record Security(
            int loginMaxAttempts,
            Duration lockoutDuration,
            Duration passwordResetTtl,
            int registrationsPerHourPerIp,
            int loginAttemptsPerWindowPerIp,
            int passwordResetsPerHourPerIp,
            int enquiriesPerHourPerIp
    ) {
    }

    /**
     * {@code enabled} switches off the scheduled worker. Tests turn it off so
     * they can drive delivery deliberately rather than racing a timer.
     */
    public record Notifications(
            boolean enabled,
            int maxAttempts,
            int batchSize,
            Duration pollInterval
    ) {
    }

    /**
     * MSG91's templated email API - used only for the order-confirmation
     * customer email today, not a general SMTP replacement. Absent an auth
     * key, the order-confirmation email falls back to the regular SMTP path
     * like everything else.
     */
    public record Msg91(String authKey, String fromEmail, String domain, String orderConfirmationTemplateId) {
        public boolean isConfigured() {
            return authKey != null && !authKey.isBlank();
        }
    }
}
