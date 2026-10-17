package nl.jimkaplan.autotrader.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.model.document.PasswordResetToken;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import nl.jimkaplan.autotrader.repository.PasswordResetTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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
     * A repeat request inside this window keeps the link that is already on its way and sends
     * nothing: a double-click must not invalidate the mail it just asked for, and the window caps
     * how often one address can make the SMTP server work.
     */
    static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);

    /**
     * The subject of the recovery mail.
     */
    static final String RECOVERY_SUBJECT = "Reset your Autotrader password";

    /**
     * One source of randomness for the token bytes; a fresh SecureRandom per call would be seeded
     * from scratch every time.
     */
    private final SecureRandom random = new SecureRandom();

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final AppUserService appUserService;
    private final MailSettingsService mail;
    private final MongoTemplate mongoTemplate;
    private final String publicUrl;
    private final Duration lifetime;
    private final Clock clock;
    private final Executor mailExecutor;

    @Autowired
    public PasswordResetService(AppUserRepository users, PasswordResetTokenRepository tokens,
                                AppUserService appUserService, MailSettingsService mail,
                                MongoTemplate mongoTemplate, Executor recoveryMailExecutor,
                                @Value("${autotrader.security.public-url:http://localhost:8081/autotrader}") String publicUrl,
                                @Value("${autotrader.security.reset-token-minutes:30}") long minutes) {
        this(users, tokens, appUserService, mail, mongoTemplate, recoveryMailExecutor, publicUrl,
                minutes, Clock.systemUTC());
    }

    PasswordResetService(AppUserRepository users, PasswordResetTokenRepository tokens,
                         AppUserService appUserService, MailSettingsService mail,
                         MongoTemplate mongoTemplate, Executor mailExecutor, String publicUrl,
                         long minutes, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.appUserService = appUserService;
        this.mail = mail;
        this.mongoTemplate = mongoTemplate;
        this.mailExecutor = mailExecutor;
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
                if (stillOnItsWay(user)) {
                    log.info("A recovery link for {} was requested again inside the cooldown; "
                            + "nothing was sent", user.getUsername());
                    return;
                }
                // Mint and store the token here, on the request thread, so the cooldown sees it
                // straight away; only the mail itself leaves on the worker, where a slow SMTP
                // server makes no difference to how long this answer takes.
                String token = newToken();
                String mintedHash = hash(token);
                Optional<PasswordResetToken> previous = mint(user, mintedHash);
                String body = message(user, token);
                try {
                    mailExecutor.execute(() -> sendQuietly(user, body, previous, mintedHash));
                } catch (RejectedExecutionException e) {
                    // The worker's queue is full: the token just minted was never mailed, so the
                    // link already in the reader's inbox takes its place again. An overload must
                    // answer like any other request, and must not leave them without a working
                    // link.
                    restore(user, previous, mintedHash);
                    log.warn("The recovery mail for {} was not queued: the worker is busy",
                            user.getUsername(), e);
                }
            });
        }
        return SENT_MESSAGE;
    }

    private void sendQuietly(AppUser user, String body, Optional<PasswordResetToken> previous,
                             String mintedHash) {
        try {
            mail.send(user.getEmail(), RECOVERY_SUBJECT, body);
        } catch (RuntimeException e) {
            // The new link never left either: put the previous one back, so a mail server that is
            // down does not cost the reader the link they already hold.
            restore(user, previous, mintedHash);
            log.warn("The recovery mail for account {} could not be sent", user.getUsername(), e);
        }
    }

    /**
     * Put the token that was stored before this request back (or clear the account's token when
     * there was none), because the replacement never reached anybody — and only while the stored
     * token is still the one this request minted: another request may have replaced it meanwhile,
     * and undoing that would take away a link that is already on its way. {@code used_time} is left
     * guard asks for {@code used_time: null} — the state this request's mint left — so a claim that
     * landed in this window keeps its mark and stays spent, while the earlier document otherwise
     * comes back exactly as it was, its own spent mark included.
     *
     * <p>Two limits are accepted rather than engineered away. A send that fails after the server
     * accepted the message — a missing final {@code 250}, a read timeout — has been delivered, and
     * this puts the previous token back over it; when that token has expired meanwhile, the reader
     * is left with no working link where they had one. And because the token put back normally
     * predates the cooldown, repeated requests do reach the SMTP server again while sends are
     * failing: {@link #RESEND_COOLDOWN} caps repeats only while links are minted and delivered.</p>
     *
     * @param user       The account
     * @param previous   What was stored before this request
     * @param mintedHash The hash of the token this request minted
     */
    private void restore(AppUser user, Optional<PasswordResetToken> previous, String mintedHash) {
        // `used_time: null` is the state this request's mint left: a claim that landed in the window
        // has marked the document spent, and the restore must then leave it spent.
        Query stillOurs = Query.query(Criteria.where("_id").is(user.getId())
                .and("token_hash").is(mintedHash)
                .and("used_time").is(null));
        previous.ifPresentOrElse(
                old -> mongoTemplate.updateFirst(stillOurs, new Update()
                                .set("token_hash", old.getTokenHash())
                                .set("created_time", old.getCreatedTime())
                                .set("expires_time", old.getExpiresTime())
                                .set("used_time", old.getUsedTime()),
                        PasswordResetToken.class),
                () -> mongoTemplate.remove(stillOurs, PasswordResetToken.class));
    }

    /**
     * @param user The account
     * @return Whether an unused, unexpired link was issued so recently that it may still be on its
     *         way
     */
    private boolean stillOnItsWay(AppUser user) {
        Instant now = clock.instant();
        return tokens.findById(user.getId())
                .filter(token -> token.getUsedTime() == null
                        && token.getExpiresTime() != null
                        && token.getExpiresTime().isAfter(now))
                .filter(token -> token.getCreatedTime() != null
                        && token.getCreatedTime().isAfter(now.minus(RESEND_COOLDOWN)))
                .isPresent();
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
        PasswordResetToken stored = live(token).orElseThrow(this::noLongerValid);
        AppUser user = users.findById(stored.getId()).orElseThrow(this::noLongerValid);
        // The rules are checked before the token is claimed, so a typo does not spend the link.
        appUserService.validatePassword(password, confirm);
        if (!claim(token)) {
            // Another request claimed the token between the read and the write.
            throw noLongerValid();
        }
        appUserService.setPassword(user, password, confirm);
        log.info("Account {} set a new password through a recovery link", user.getUsername());
    }

    /**
     * Spend the token in one step: only the request that matches an unused, unexpired token and
     * actually marks it used may continue, so two overlapping submissions cannot both set a
     * password.
     *
     * @param token The token from the link
     * @return Whether this request claimed it
     */
    private boolean claim(String token) {
        Query query = Query.query(Criteria.where("token_hash").is(hash(token))
                .and("used_time").is(null)
                .and("expires_time").gt(clock.instant()));
        Update update = new Update().set("used_time", clock.instant());
        return mongoTemplate.updateFirst(query, update, PasswordResetToken.class).getModifiedCount() == 1;
    }

    private IllegalArgumentException noLongerValid() {
        return new IllegalArgumentException(
                "This recovery link is no longer valid — request a new one.");
    }

    /**
     * Store a minted link, replacing whatever was there: one live token per account.
     *
     * <p>The document it replaces is read in the same round trip, so it is the state just before
     * the replacement: a claim that landed a moment earlier is visible in it, and a restore cannot
     * put a spent link back as a working one.</p>
     *
     * @param user       The account
     * @param mintedHash The hash of the token the mail carries
     * @return What was stored before, when anything was
     */
    private Optional<PasswordResetToken> mint(AppUser user, String mintedHash) {
        Instant now = clock.instant();
        PasswordResetToken minted = PasswordResetToken.builder()
                .id(user.getId())
                .tokenHash(mintedHash)
                .createdTime(now)
                .expiresTime(now.plus(lifetime))
                .build();
        return Optional.ofNullable(mongoTemplate.findAndReplace(
                Query.query(Criteria.where("_id").is(user.getId())), minted,
                FindAndReplaceOptions.options().upsert()));
    }

    private String message(AppUser user, String token) {
        return "A password reset was requested for your Autotrader account (" + user.getUsername()
                + ").\n\nOpen this link within " + lifetime.toMinutes()
                + " minutes to set a new password:\n\n" + publicUrl + "/reset-password?token="
                + token + "\n\nIf you did not request this, you can ignore this message — "
                + "your password stays as it is.";
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
