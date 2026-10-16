package nl.jimkaplan.autotrader.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.controller.MailSettingsResponse;
import nl.jimkaplan.autotrader.model.document.MailSettings;
import nl.jimkaplan.autotrader.repository.MailSettingsRepository;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * The SMTP server the application sends its mail through — the password-recovery messages today.
 *
 * <p>The settings live in the database rather than in environment variables, so a fresh
 * installation can be configured from the settings page; the password is stored encrypted and is
 * write-only, and the sender is built per message so a settings change takes effect immediately.</p>
 */
@Slf4j
@Service
public class MailSettingsService {

    /**
     * Bounded waits, so a wrong host fails the request instead of hanging the page.
     */
    private static final String TIMEOUT_MILLIS = "10000";

    private final MailSettingsRepository repository;
    private final EncryptionService encryptionService;

    public MailSettingsService(MailSettingsRepository repository,
                               EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    /**
     * @return The stored settings with the plaintext password filled in, or empty when SMTP is not
     *         configured yet
     */
    public Optional<MailSettings> get() {
        return repository.findById(MailSettings.ID).map(this::decrypt);
    }

    /**
     * @return Whether an SMTP server is configured
     */
    public boolean configured() {
        return repository.existsById(MailSettings.ID);
    }

    /**
     * @return The masked view the settings page and the API read
     */
    public MailSettingsResponse view() {
        return get().map(settings -> new MailSettingsResponse(true, settings.getHost(),
                        settings.getPort(), settings.getUsername(), settings.getFromAddress(),
                        settings.isStartTls(), settings.getUpdatedTime()))
                .orElseGet(() -> new MailSettingsResponse(false, null, null, null, null, true, null));
    }

    /**
     * Store the SMTP server, replacing whatever was configured before. A blank password keeps the
     * stored one, so a host or address can be corrected without re-typing the password.
     *
     * @param host        The SMTP host
     * @param port        The port; the default (587 with STARTTLS, 25 without) when null
     * @param username    The sign-in name, when the server wants one
     * @param password    The password, or blank to keep the stored one
     * @param fromAddress The address the mails come from
     * @param startTls    Whether STARTTLS is used
     * @return The stored settings with their plaintext password filled in
     * @throws IllegalArgumentException when the host or the from-address is missing, or the server
     *                                  asks for a username and no password is stored or given
     */
    public MailSettings save(String host, Integer port, String username, String password,
                             String fromAddress, boolean startTls) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("The SMTP host is required");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalArgumentException("The address the mails come from is required");
        }
        String name = username == null || username.isBlank() ? null : username.trim();
        Optional<MailSettings> stored = get();
        String encrypted = stored.map(MailSettings::getEncryptedPassword).orElse(null);
        if (password != null && !password.isBlank()) {
            encrypted = encryptionService.encrypt(password);
        } else if (encrypted == null && name != null) {
            // A server without a username wants no password at all; one with a username needs it the
            // first time it is stored (afterwards a blank field keeps the stored one).
            throw new IllegalArgumentException(
                    "The SMTP password is required when the server asks for a username");
        }
        MailSettings settings = repository.save(MailSettings.builder()
                .id(MailSettings.ID)
                .host(host.trim())
                .port(resolvedPort(port, startTls))
                .username(name)
                .encryptedPassword(encrypted)
                .fromAddress(fromAddress.trim())
                .startTls(startTls)
                .updatedTime(Instant.now())
                .build());
        log.info("The SMTP settings were stored: {}:{}", settings.getHost(), settings.getPort());
        return decrypt(settings);
    }

    /**
     * Send a plain-text message through the configured server.
     *
     * @param to      The recipient
     * @param subject The subject
     * @param body    The text
     * @throws IllegalStateException when SMTP is not configured
     */
    public void send(String to, String subject, String body) {
        MailSettings settings = get().orElseThrow(() -> new IllegalStateException(
                "No SMTP server is configured yet; add one on the settings page."));
        JavaMailSenderImpl sender = sender(settings);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.getFromAddress());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }

    JavaMailSenderImpl sender(MailSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(resolvedPort(settings.getPort(), settings.isStartTls()));
        boolean authenticated = settings.getUsername() != null && !settings.getUsername().isBlank();
        if (authenticated) {
            sender.setUsername(settings.getUsername());
            sender.setPassword(settings.getPassword());
        }
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", String.valueOf(authenticated));
        properties.put("mail.smtp.starttls.enable", String.valueOf(settings.isStartTls()));
        properties.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        properties.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        return sender;
    }

    /**
     * @param port     The stored or submitted port, when there is one
     * @param startTls Whether STARTTLS is used
     * @return The port to use: the one given, or the usual default for that transport
     */
    static int resolvedPort(Integer port, boolean startTls) {
        return port != null ? port : (startTls ? 587 : 25);
    }

    private MailSettings decrypt(MailSettings settings) {
        settings.setPassword(settings.getEncryptedPassword() == null ? null
                : encryptionService.decrypt(settings.getEncryptedPassword()));
        return settings;
    }
}
