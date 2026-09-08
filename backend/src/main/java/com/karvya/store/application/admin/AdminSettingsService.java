package com.karvya.store.application.admin;

import com.karvya.store.application.media.ImageUploadValidator;
import com.karvya.store.application.media.LogoService;
import com.karvya.store.application.settings.SettingsService;
import com.karvya.store.application.settings.ThemeFonts;
import com.karvya.store.application.notification.EmailSender;
import com.karvya.store.infrastructure.mail.MailSenderProvider;
import com.karvya.store.domain.ConflictException;
import com.karvya.store.domain.FieldValidationException;
import com.karvya.store.domain.NotFoundException;
import com.karvya.store.domain.model.SettingType;
import com.karvya.store.domain.model.SiteSetting;
import com.karvya.store.domain.repository.SiteSettingRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Reading and changing site settings.
 *
 * <p>Every value is validated against its declared type before it is stored,
 * and rich text is sanitised. Settings are rendered into pages customers see,
 * so an administrator account - or anyone who takes one over - must not be
 * able to turn a policy page into a script delivery mechanism.
 */
@Service
public class AdminSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsService.class);

    /**
     * {@code placeholder} and {@code unset} are separate states, not one.
     * Seeded copy still saying [PLACEHOLDER] is visibly wrong to a customer;
     * an empty WhatsApp number silently removes a feature instead. Both need
     * attention before launch, but they are different problems and the screen
     * should say which.
     */
    public record SettingView(
            String key,
            String value,
            SettingType valueType,
            String description,
            boolean placeholder,
            boolean unset
    ) {
        static SettingView from(SiteSetting setting) {
            boolean unset = setting.getValue() == null || setting.getValue().isBlank();
            // A secret is reported as stored or not, never echoed. Returning it
            // would put an SMTP password in every admin's browser history and
            // in any log that captured the response.
            String value = setting.getValueType() == SettingType.SECRET ? null : setting.getValue();
            return new SettingView(setting.getKey(), value, setting.getValueType(),
                    setting.getDescription(), setting.isPlaceholder(), unset);
        }
    }

    public record Update(
            @NotNull(message = "Send the settings to change")
            @Size(max = 200)
            Map<String, String> values
    ) {
    }

    /**
     * What rich-text settings may contain.
     *
     * <p>Formatting and links, nothing that executes. jsoup's basicWithImages
     * excludes script, style, iframe, object and every on* handler; the extra
     * protocol rule keeps {@code javascript:} out of hrefs.
     */
    private static final Safelist RICH_TEXT = Safelist.basicWithImages()
            .addAttributes("a", "href", "title", "rel")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https");

    private final SiteSettingRepository repository;
    private final SettingsService settings;
    private final EmailSender emails;
    private final MailSenderProvider mailSenders;
    private final LogoService logos;
    private final ImageUploadValidator imageValidator;

    public AdminSettingsService(SiteSettingRepository repository, SettingsService settings,
                                EmailSender emails, MailSenderProvider mailSenders,
                                LogoService logos, ImageUploadValidator imageValidator) {
        this.repository = repository;
        this.settings = settings;
        this.emails = emails;
        this.mailSenders = mailSenders;
        this.logos = logos;
        this.imageValidator = imageValidator;
    }

    @Transactional(readOnly = true)
    public List<SettingView> listAll() {
        return repository.findAllByOrderByKeyAsc().stream().map(SettingView::from).toList();
    }

    /**
     * Applies a batch of changes.
     *
     * <p>Everything is validated first and written second, so a form with one
     * bad field leaves nothing half-applied.
     */
    @Transactional
    public List<SettingView> update(Map<String, String> values, String actor) {
        Map<String, SiteSetting> known = repository.findAllByOrderByKeyAsc().stream()
                .collect(java.util.stream.Collectors.toMap(SiteSetting::getKey, s -> s));

        // Validate the whole batch before touching anything, and collect every
        // failure rather than stopping at the first. A form with three bad
        // values should be correctable in one pass, and the interface needs to
        // know which fields to mark - not just that something was wrong.
        Map<String, String> cleaned = new java.util.LinkedHashMap<>();
        Map<String, String> problems = new java.util.LinkedHashMap<>();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            SiteSetting setting = known.get(entry.getKey());
            if (setting == null) {
                // an unknown key is a client bug, not something to create
                throw new NotFoundException("Setting", entry.getKey());
            }
            // The logo key names a file the upload wrote. Typing it by hand can
            // only point the shop at something that is not there, or orphan the
            // files behind the real one, so it is not editable as a value - it
            // is changed by uploading or removing a logo.
            if (SettingsService.LOGO_KEY.equals(entry.getKey())) {
                throw new ConflictException("logo-not-editable",
                        "The logo is changed by uploading one under Appearance, "
                                + "not by editing this value.");
            }

            // The form cannot show a secret, so it submits an empty field for
            // one it is not changing. Treating that as a clear would wipe the
            // SMTP password every time an unrelated setting was saved.
            boolean blank = entry.getValue() == null || entry.getValue().isBlank();
            if (setting.getValueType() == SettingType.SECRET && blank) {
                continue;
            }

            try {
                cleaned.put(entry.getKey(), coerce(setting, entry.getValue()));
            } catch (ConflictException e) {
                problems.put(entry.getKey(), e.getMessage());
            }
        }

        if (!problems.isEmpty()) {
            throw new FieldValidationException(problems);
        }

        cleaned.forEach((key, value) -> known.get(key).setValue(value, actor));
        repository.flush();
        settings.reload();

        log.info("{} updated {} settings", actor, cleaned.size());
        return listAll();
    }

    /**
     * Sends a message to the given address using whatever mail configuration is
     * current, and reports what happened.
     *
     * <p>Deliberately not queued through the outbox. The outbox exists to hide
     * a delivery failure from a shopper and retry it later, which is exactly
     * the wrong behaviour here: the administrator is asking whether it works
     * right now, so the answer has to be synchronous and has to include the
     * error when there is one.
     */
    public Map<String, Object> sendTestEmail(String recipient) {
        String source = mailSenders.isConfiguredInSettings() ? "site settings" : "MAIL_* environment";
        try {
            emails.send(recipient,
                    "Rainbow Books test message",
                    "<p>Your shop can send email.</p>"
                            + "<p>This was sent using the configuration from <strong>"
                            + source + "</strong>, from " + mailSenders.from() + ".</p>");

            log.info("Test email sent to {} using {}", recipient, source);
            return Map.of("sent", true, "recipient", recipient, "source", source);

        } catch (RuntimeException e) {
            // the cause carries the provider's own words - "authentication
            // failed", "relay denied" - which is the whole value of the button
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            log.warn("Test email to {} failed: {}", recipient, root.getMessage());
            return Map.of("sent", false, "recipient", recipient, "source", source,
                    "error", String.valueOf(root.getMessage()));
        }
    }

    /**
     * Replaces the shop's logo.
     *
     * <p>The previous one is removed only once the new key is safely stored, so
     * a failure part way through leaves the old mark rather than none.
     */
    @Transactional
    public String replaceLogo(MultipartFile image, String actor) {
        String previous = settings.find(SettingsService.LOGO_KEY).orElse(null);
        String key = logos.store(imageValidator.validate(image).bytes());

        repository.findById(SettingsService.LOGO_KEY)
                .ifPresent(setting -> setting.setValue(key, actor));
        repository.flush();
        settings.reload();

        if (previous != null && !previous.equals(key)) {
            logos.delete(previous);
        }

        log.info("{} set the shop logo", actor);
        return key;
    }

    /** Removes it, and the files behind it. The shop falls back to its name. */
    @Transactional
    public void removeLogo(String actor) {
        String previous = settings.find(SettingsService.LOGO_KEY).orElse(null);

        repository.findById(SettingsService.LOGO_KEY)
                .ifPresent(setting -> setting.setValue(null, actor));
        repository.flush();
        settings.reload();

        if (previous != null) {
            logos.delete(previous);
        }
        log.info("{} removed the shop logo", actor);
    }

    private static final java.util.regex.Pattern HEX_COLOUR =
            java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** Validates and normalises one value against its declared type. */
    private String coerce(SiteSetting setting, String raw) {
        String value = (raw == null || raw.isBlank()) ? null : raw.trim();
        if (value == null) {
            return null;
        }

        return switch (setting.getValueType()) {
            case INTEGER -> {
                try {
                    yield String.valueOf(Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    throw invalid(setting, "a whole number");
                }
            }
            case DECIMAL -> {
                try {
                    BigDecimal parsed = new BigDecimal(value);
                    if (parsed.signum() < 0) {
                        throw invalid(setting, "an amount of zero or more");
                    }
                    yield parsed.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
                } catch (NumberFormatException e) {
                    throw invalid(setting, "an amount such as 80.00");
                }
            }
            case BOOLEAN -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw invalid(setting, "true or false");
                }
                yield value.toLowerCase();
            }
            case URL -> {
                try {
                    URI uri = URI.create(value);
                    if (uri.getScheme() == null
                            || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                        throw invalid(setting, "a link beginning http:// or https://");
                    }
                    yield value;
                } catch (IllegalArgumentException e) {
                    throw invalid(setting, "a valid link");
                }
            }
            // sanitised, not escaped: the point is to keep the formatting and
            // drop anything that could run
            case HTML -> Jsoup.clean(value, RICH_TEXT);
            case JSON -> value;
            case COLOUR -> {
                String colour = value.startsWith("#") ? value : "#" + value;
                if (!HEX_COLOUR.matcher(colour).matches()) {
                    throw invalid(setting, "a colour such as #A33B2E");
                }
                // stored in one case so two spellings of the same colour do not
                // read as a change in the admin form
                yield colour.toUpperCase(java.util.Locale.ROOT);
            }
            case FONT -> {
                if (!ThemeFonts.isAllowed(value)) {
                    throw invalid(setting, "one of: " + String.join(", ", ThemeFonts.names()));
                }
                yield value;
            }
            // stored verbatim: an API key is not markup and must not be
            // altered on the way in
            case SECRET -> value;
            // Markup removed, line breaks kept. jsoup tidies whitespace by
            // default, which turned a postal address into one long line and
            // collapsed the blank line between two paragraphs of story copy -
            // so the setting could be typed over several lines and never stored
            // that way. prettyPrint(false) is what leaves the text as written.
            case STRING, TEXT -> Jsoup.clean(
                    value, "", Safelist.none(),
                    new Document.OutputSettings().prettyPrint(false));
        };
    }

    /**
     * The message shown under the offending field, so it does not repeat the
     * key the field is already labelled with. The key is added back by
     * FieldValidationException when a single failure is summarised as a banner.
     */
    private ConflictException invalid(SiteSetting setting, String expected) {
        return new ConflictException("invalid-setting-value", "Needs " + expected + ".");
    }
}
