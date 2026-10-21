package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.controller.MailSettingsResponse;
import nl.jimkaplan.autotrader.model.document.MailSettings;
import nl.jimkaplan.autotrader.repository.MailSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SMTP settings: stored encrypted, read masked, a blank password that keeps the stored one, and
 * the sender built from what was stored.
 */
class MailSettingsServiceTest {

    private final MailSettingsRepository repository = mock(MailSettingsRepository.class);
    // A full AES-256 key: the master key is base64 and used as-is.
    private final EncryptionService encryption = new EncryptionService(
            Base64.getEncoder().encodeToString(new byte[32]));
    private final MailSettingsService service = new MailSettingsService(repository, encryption);

    private MailSettings stored;

    @BeforeEach
    void wireTheStore() {
        when(repository.save(any())).thenAnswer(invocation -> {
            stored = invocation.getArgument(0);
            return stored;
        });
        when(repository.findById(MailSettings.ID)).thenAnswer(invocation -> Optional.ofNullable(stored));
        when(repository.existsById(MailSettings.ID)).thenAnswer(invocation -> stored != null);
    }

    @Test
    void beforeAnythingIsStoredTheViewSaysSo() {
        MailSettingsResponse view = service.view();

        assertFalse(view.configured());
        assertNull(view.host());
        assertTrue(view.startTls());
        assertFalse(service.configured());
    }

    @Test
    void saveStoresThePasswordEncryptedAndReturnsItPlain() {
        MailSettings saved = service.save("smtp.example.com", null, "mailer", "smtp-secret",
                "autotrader@example.com", true);

        assertNotEquals("smtp-secret", stored.getEncryptedPassword());
        assertEquals("smtp-secret", encryption.decrypt(stored.getEncryptedPassword()));
        assertEquals("smtp-secret", saved.getPassword());
        assertEquals(587, saved.getPort());

        MailSettingsResponse view = service.view();
        assertTrue(view.configured());
        assertEquals("smtp.example.com", view.host());
        assertEquals("autotrader@example.com", view.fromAddress());
        assertTrue(view.startTls());
    }

    @Test
    void aBlankPasswordKeepsTheStoredOne() {
        service.save("smtp.example.com", 587, "mailer", "smtp-secret", "a@example.com", true);

        MailSettings saved = service.save("smtp2.example.com", 2525, "mailer", "  ",
                "b@example.com", false);

        assertEquals("smtp-secret", saved.getPassword());
        assertEquals("smtp2.example.com", stored.getHost());
        assertEquals(2525, stored.getPort());
        assertFalse(stored.isStartTls());
    }

    @Test
    void aServerThatAsksForAUsernameNeedsAPassword() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.save("smtp.example.com", 587, "mailer", null, "a@example.com", true));

        assertTrue(error.getMessage().contains("password is required"));
    }

    @Test
    void aServerWithoutAUsernameNeedsNoPasswordAtAll() {
        // The local relay on this box wants no sign-in; the settings should not force a password on
        // it, and none should be stored.
        MailSettings saved = service.save("127.0.0.1", 25, null, null, "noreply@example.com", false);

        assertNull(saved.getPassword());
        assertNull(stored.getEncryptedPassword());
        assertTrue(service.view().configured());
    }

    @Test
    void hostAndFromAddressAreRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(" ", 587, "mailer", "secret", "a@example.com", true));
        assertThrows(IllegalArgumentException.class,
                () -> service.save("smtp.example.com", 587, "mailer", "secret", null, true));
    }

    @Test
    void thePortDefaultsByStartTls() {
        assertEquals(25, service.save("smtp.example.com", null, null, "secret", "a@example.com", false)
                .getPort());
        assertEquals(587, MailSettingsService.resolvedPort(null, true));
        assertEquals(25, MailSettingsService.resolvedPort(null, false));
        assertEquals(2525, MailSettingsService.resolvedPort(2525, true));
    }

    @Test
    void theSenderCarriesTheStoredSettings() {
        MailSettings settings = service.save("smtp.example.com", 587, "mailer", "smtp-secret",
                "autotrader@example.com", true);

        JavaMailSenderImpl sender = service.sender(settings);

        assertEquals("smtp.example.com", sender.getHost());
        assertEquals(587, sender.getPort());
        assertEquals("mailer", sender.getUsername());
        assertEquals("smtp-secret", sender.getPassword());
        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.starttls.enable"));
        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.auth"));
    }

    @Test
    void anAnonymousServerAsksForNoPassword() {
        MailSettings settings = service.save("smtp.example.com", 25, null, "secret",
                "a@example.com", false);

        JavaMailSenderImpl sender = service.sender(settings);

        assertNull(sender.getUsername());
        assertEquals("false", sender.getJavaMailProperties().get("mail.smtp.auth"));
        assertEquals("false", sender.getJavaMailProperties().get("mail.smtp.starttls.enable"));
    }

    @Test
    void sendingUsesTheStoredServerAndTheMessageReadsAsWritten() {
        service.save("smtp.example.com", 587, "mailer", "smtp-secret", "autotrader@example.com", true);
        MailSettingsService sending = spy(service);
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        doReturn(sender).when(sending).sender(any(MailSettings.class));

        sending.send("jim@example.com", "Reset your Autotrader password", "the body");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertEquals("autotrader@example.com", message.getValue().getFrom());
        assertEquals("jim@example.com", message.getValue().getTo()[0]);
        assertEquals("Reset your Autotrader password", message.getValue().getSubject());
        assertEquals("the body", message.getValue().getText());
    }

    @Test
    void sendingWithoutConfigurationSaysSo() {
        assertThrows(IllegalStateException.class,
                () -> service.send("a@example.com", "subject", "body"));
    }
}
