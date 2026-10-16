package nl.jimkaplan.autotrader.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.model.document.PasswordResetToken;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import nl.jimkaplan.autotrader.repository.PasswordResetTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Password recovery by mail: a request always answers the same way, whether or not the address
 * belongs to the account, and only a matching address is sent a link.
 *
 * <p>The link carries a 256-bit random token; the database stores its SHA-256 hash, so the stored
 * data cannot be turned back into a working link. Tokens are single-use and expire, and asking
 * again replaces the previous one.</p>
 */
@Slf4j
@Service
public class PasswordResetService {

    /**
     * The one answer a recovery request ever gets — an address that is not the account's must be
     * indistinguishable from one that is.
     */
    public static final String SENT_MESSAGE =
            "We'll send a recovery email to this address if it exists.";

    /**
     * One source of randomness for the token bytes; a fresh SecureRandom per call would be seeded
     * from scratch every time.
     */
    private final SecureRandom random = new SecureRandom();

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final AppUserService appUserService;
    private final MailSettingsService mail;
    private final Clock clock;
    private final String publicUrl;
    private final Duration lifetime;

    @Autowired
    public PasswordResetService(AppUserRepository users, PasswordResetTokenRepository tokens,
                                AppUserService appUserService, MailSettingsService mail,
                                @Value("${autotrader.security.public-url:http://localhost:8081/autotrader}") String publicUrl,
                                @Value("${autotrader.security.reset-token-minutes:30}") long minutes) {
        this(users, tokens, appUserService, mail, publicUrl, minutes, Clock.systemUTC());
    }

    PasswordResetService(AppUserRepository users, PasswordResetTokenRepository tokens,
                         AppUserService appUserService, MailSettingsService mail, String publicUrl,
                         long minutes, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.appUserService = appUserService;
        this.mail = mail;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.lifetime = Duration.ofMinutes(minutes);
        this.clock = clock;
    }

    /**
     * Ask for a recovery mail. Always the same answer; a mail goes out only when the address
     * belongs to the account, and a failing mail server is logged, not shown.
     *
     * @param email The submitted address
     * @return {@link #SENT_MESSAGE}, whatever happened
     */
    public String request(String email) {
        String address = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (address != null && !address.isEmpty()) {
            users.findByEmail(address).ifPresent(user -> {
                try {
                    sendLink(user);
                } catch (RuntimeException e) {
                    log.warn("The recovery mail for account {} could not be sent", user.getUsername(), e);
                }
            });
        }
        return SENT_MESSAGE;
    }

    /**
     * @param token The token from the link
     * @return Whether the token is live: known, unused and unexpired
     */
    public boolean valid(String token) {
        return live(token).isPresent();
    }

    /**
     * Set a new password from a recovery link. The token is spent in the same step, so the link
     * cannot be used twice.
     *
     * @param token    The token from the link
     * @param password The new password
     * @param confirm  The new password again
     * @throws IllegalArgumentException when the token is not live or the password breaks a rule
     */
    public void reset(String token, String password, String confirm) {
        PasswordResetToken stored = live(token).orElseThrow(() -> new IllegalArgumentException(
                "This recovery link is no longer valid — request a new one."));
        AppUser user = users.findById(stored.getId()).orElseThrow(() -> new IllegalArgumentException(
                "This recovery link is no longer valid — request a new one."));
        appUserService.setPassword(user, password, confirm);
        stored.setUsedTime(clock.instant());
        tokens.save(stored);
        log.info("Account {} set a new password through a recovery link", user.getUsername());
    }

    private void sendLink(AppUser user) {
        Instant now = clock.instant();
        String token = newToken();
        tokens.save(PasswordResetToken.builder()
                .id(user.getId())
                .tokenHash(hash(token))
                .createdTime(now)
                .expiresTime(now.plus(lifetime))
                .build());
        mail.send(user.getEmail(), "Reset your Autotrader password",
                "A password reset was requested for your Autotrader account (" + user.getUsername()
                        + ").\n\nOpen this link within " + lifetime.toMinutes()
                        + " minutes to set a new password:\n\n" + publicUrl + "/reset-password?token="
                        + token + "\n\nIf you did not request this, you can ignore this message — "
                        + "your password stays as it is.");
    }

    private Optional<PasswordResetToken> live(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return tokens.findByTokenHash(hash(token))
                .filter(stored -> stored.getUsedTime() == null
                        && stored.getExpiresTime() != null
                        && stored.getExpiresTime().isAfter(now));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to hash recovery tokens", e);
        }
    }
}
